// SPDX-License-Identifier: BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)

package io.github.muntashirakon.adb;

import androidx.annotation.GuardedBy;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class abstracts the underlying ADB streams
 */
// Copyright 2013 Cameron Gutman
public class AdbStream implements Closeable {

    /**
     * The AdbConnection object that the stream communicates over
     */
    private final AdbConnection mAdbConnection;

    /**
     * The local ID of the stream
     */
    private final int mLocalId;

    /**
     * The remote ID of the stream
     */
    private volatile int mRemoteId;

    /**
     * Indicates whether WRTE is currently allowed
     */
    private final AtomicBoolean mWriteReady;

    /**
     * How many bytes may sit unread in {@link #mReadQueue} before OKAY packets are withheld,
     * as a multiple of the connection's maxData.
     */
    private static final int READ_QUEUE_BACKLOG_FACTOR = 8;

    /**
     * An empty buffer used as the initial read buffer, before the first payload arrives.
     */
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.wrap(new byte[0]);

    /**
     * A queue of data from the target's WRTE packets
     */
    private final Queue<byte[]> mReadQueue;

    /**
     * Maximum number of bytes allowed to sit in {@link #mReadQueue}. Once the limit is reached, the OKAY for the
     * last received WRTE packet is deferred until the application drains the queue, which stops the peer from
     * sending more data. This bounds the memory used by a stream regardless of how fast the peer produces data.
     */
    private final int mMaxQueuedData;

    /**
     * Number of payload bytes currently in {@link #mReadQueue}.
     */
    @GuardedBy("mReadQueue")
    private long mQueuedData;

    /**
     * Whether an OKAY packet is owed to the peer once the read queue drains below {@link #mMaxQueuedData}.
     */
    @GuardedBy("mReadQueue")
    private boolean mReadyPending;

    /**
     * Holds the payload currently being consumed by the reader. Wraps the arrays received from WRTE packets.
     */
    private volatile ByteBuffer mReadBuffer = EMPTY_BUFFER;

    /**
     * Indicates whether the connection is closed already
     */
    private volatile boolean mIsClosed;

    /**
     * Whether the remote peer has closed but we still have unread data in the queue
     */
    private volatile boolean mPendingClose;

    /**
     * Whether the stream was closed by the remote peer (graceful end of stream) as opposed to a local close or a
     * dead connection. Only a peer-initiated close may be reported as a regular end of stream to readers; anything
     * else has to surface as an error so that a truncated transfer is never mistaken for a complete one.
     */
    private volatile boolean mClosedByPeer;

    /**
     * Creates a new AdbStream object on the specified AdbConnection
     * with the given local ID.
     *
     * @param adbConnection AdbConnection that this stream is running on
     * @param localId       Local ID of the stream
     */
    AdbStream(AdbConnection adbConnection, int localId)
            throws IOException, InterruptedException, AdbPairingRequiredException {
        this.mAdbConnection = adbConnection;
        this.mLocalId = localId;
        this.mReadQueue = new ConcurrentLinkedQueue<>();
        this.mMaxQueuedData = (int) Math.min((long) adbConnection.getMaxData() * READ_QUEUE_BACKLOG_FACTOR,
                Integer.MAX_VALUE);
        this.mWriteReady = new AtomicBoolean(false);
        this.mIsClosed = false;
    }

    public AdbInputStream openInputStream() {
        return new AdbInputStream(this);
    }

    public AdbOutputStream openOutputStream() {
        return new AdbOutputStream(this);
    }

    /**
     * Called by the connection thread to indicate newly received data.
     *
     * @param payload Data inside the WRTE message
     * @return {@code true} if the peer may be sent an OKAY right away, {@code false} if the read queue is full and
     * the OKAY has to be deferred until the application drains the queue (see {@link #read(byte[], int, int)}).
     */
    boolean addPayload(byte[] payload) {
        synchronized (mReadQueue) {
            mReadQueue.add(payload);
            mQueuedData += payload.length;
            mReadQueue.notifyAll();
            if (mQueuedData < mMaxQueuedData) {
                return true;
            }
            mReadyPending = true;
            return false;
        }
    }

    /**
     * Called by the connection thread to send an OKAY packet, allowing the
     * other side to continue transmission.
     *
     * @throws IOException If the connection fails while sending the packet
     */
    void sendReady() throws IOException {
        // Generate and send a OKAY packet
        mAdbConnection.sendPacket(AdbProtocol.generateReady(mLocalId, mRemoteId));
    }

    /**
     * Called by the connection thread to update the remote ID for this stream
     *
     * @param remoteId New remote ID
     */
    void updateRemoteId(int remoteId) {
        this.mRemoteId = remoteId;
    }

    /**
     * Called by the connection thread to indicate the stream is okay to send data.
     */
    void readyForWrite() {
        mWriteReady.set(true);
    }

    /**
     * Called by the connection thread to notify that the stream was closed by the peer.
     */
    void notifyClose(boolean closedByPeer) {
        // We don't call close() because it sends another CLSE
        synchronized (mReadQueue) {
            if (closedByPeer) {
                mClosedByPeer = true;
            }
            if (closedByPeer && (!mReadQueue.isEmpty() || mReadBuffer.hasRemaining())) {
                // The remote peer closed the stream, but we haven't finished reading the remaining data
                mPendingClose = true;
            } else {
                mIsClosed = true;
            }
            mReadQueue.notifyAll();
        }

        // Notify readers and writers
        synchronized (this) {
            notifyAll();
        }
    }

    /**
     * Read bytes from the ADB daemon.
     *
     * @return the next byte of data, or {@code -1} if the end of the stream is reached.
     * @throws IOException If the stream fails while waiting
     */
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (mReadBuffer.hasRemaining()) {
            return readBuffer(bytes, offset, length);
        }
        // Buffer has no data, grab the next payload from the queue
        byte[] data;
        boolean sendAck = false;
        boolean eof = false;
        synchronized (mReadQueue) {
            // Wait for the stream to close or data to be received
            while ((data = mReadQueue.poll()) == null && !mIsClosed && !mPendingClose) {
                try {
                    mReadQueue.wait();
                } catch (InterruptedException e) {
                    //noinspection UnnecessaryInitCause
                    throw (IOException) new IOException().initCause(e);
                }
            }
            if (data != null) {
                mQueuedData -= data.length;
                // The buffer has to be swapped while holding the lock so that notifyClose() sees the unread
                // data and keeps the stream open until it has been consumed.
                mReadBuffer = ByteBuffer.wrap(data);
                if (mReadyPending && mQueuedData < mMaxQueuedData) {
                    // We owe the peer an OKAY for the last WRTE packet: the queue has room again
                    mReadyPending = false;
                    sendAck = true;
                }
            } else if (mPendingClose) {
                // The peer closed the stream, and we've finished reading the stream data, so this stream is finished
                mIsClosed = true;
                eof = true;
            } else if (mClosedByPeer) {
                // The peer closed the stream with nothing left to read: a graceful end of stream
                eof = true;
            }
        }
        // The OKAY is sent outside the queue lock so a blocking socket write cannot stall the connection thread
        if (sendAck) {
            sendReady();
        }

        if (data != null) {
            return readBuffer(bytes, offset, length);
        }
        if (eof) {
            return -1;
        }
        throw new IOException("Stream closed.");
    }

    private int readBuffer(byte[] bytes, int offset, int length) {
        int count = Math.min(length, mReadBuffer.remaining());
        mReadBuffer.get(bytes, offset, count);
        return count;
    }

    /**
     * Sends a WRTE packet with a given byte array payload. It does not flush the stream.
     *
     * @param bytes Payload in the form of a byte array
     * @throws IOException If the stream fails while sending data
     */
    public void write(byte[] bytes, int offset, int length) throws IOException {
        int maxData;
        try {
            maxData = mAdbConnection.getMaxData();
        } catch (InterruptedException | AdbPairingRequiredException e) {
            //noinspection UnnecessaryInitCause
            throw (IOException) new IOException().initCause(e);
        }
        boolean checksum = mAdbConnection.shouldSendChecksum();
        // Split and send data as WRTE packets of at most maxData bytes. A WRTE message may not be sent
        // until the READY (OKAY) message for the previous one has been received.
        while (length != 0) {
            synchronized (this) {
                // Make sure we're ready for a WRTE
                while (!mIsClosed && !mWriteReady.compareAndSet(true, false)) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        //noinspection UnnecessaryInitCause
                        throw (IOException) new IOException().initCause(e);
                    }
                }

                if (mIsClosed) {
                    throw new IOException("Stream closed");
                }
            }
            int chunk = Math.min(length, maxData);
            // Send the header and the payload region separately to avoid copying the payload
            mAdbConnection.sendPacket(AdbProtocol.generateWriteHeader(mLocalId, mRemoteId, bytes, offset, chunk,
                    checksum), bytes, offset, chunk);
            offset = offset + chunk;
            length = length - chunk;
        }
    }

    public void flush() throws IOException {
        if (mIsClosed) {
            throw new IOException("Stream closed");
        }
        mAdbConnection.flushPacket();
    }

    /**
     * Closes the stream. This sends a close message to the peer.
     *
     * @throws IOException If the stream fails while sending the close message.
     */
    @Override
    public void close() throws IOException {
        synchronized (this) {
            // This may already be closed by the remote host
            if (mIsClosed)
                return;

            // Notify readers/writers that we've closed
            notifyClose(false);
        }

        mAdbConnection.sendPacket(AdbProtocol.generateClose(mLocalId, mRemoteId));
    }

    /**
     * Returns whether the stream is closed or not
     *
     * @return True if the stream is close, false if not
     */
    public boolean isClosed() {
        return mIsClosed;
    }

    /**
     * Returns an estimate of available data.
     *
     * @return an estimate of the number of bytes that can be read from this stream without blocking.
     * @throws IOException if the stream is close.
     */
    public int available() throws IOException {
        synchronized (this) {
            if (mIsClosed) {
                throw new IOException("Stream closed.");
            }
            if (mReadBuffer.hasRemaining()) {
                return mReadBuffer.remaining();
            }
            byte[] data = mReadQueue.peek();
            return data == null ? 0 : data.length;
        }
    }
}
