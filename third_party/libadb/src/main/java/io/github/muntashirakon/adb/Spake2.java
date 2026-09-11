// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.math.ec.rfc7748.X25519Field;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * A minimal, self-contained SPAKE2 implementation over edwards25519, wire-compatible with
 * BoringSSL's {@code spake25519.c} (the implementation used by Android's {@code adb} pairing).
 * <p>
 * All field arithmetic is delegated to BouncyCastle's constant-time {@link X25519Field}; the
 * Edwards group layer (a complete twisted-Edwards addition law in extended coordinates and a
 * constant-time windowed scalar multiplication) and the SPAKE2 protocol are implemented here.
 * Secret scalars are only ever consumed by constant-time routines.
 */
final class Spake2 {
    enum Role {ALICE, BOB}

    static final int MAX_MSG_SIZE = 32;
    static final int MAX_KEY_SIZE = 64;

    // SPAKE2 point-generation seeds (draft-ietf-kitten-krb-spake-preauth, appendix B), as used by
    // BoringSSL. M and N are the points whose compressed encodings are SHA-256(seed).
    private static final String SEED_M = "edwards25519 point generation seed (M)";
    private static final String SEED_N = "edwards25519 point generation seed (N)";

    // Group order l = 2^252 + 27742317777372353535851937790883648493 (little-endian).
    private static final byte[] L = hexToBytes("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010");

    // edwards25519 constants in BouncyCastle's X25519Field limb representation.
    // C_D  = d   = -121665/121666
    // C_D2 = 2*d
    private static final int[] C_D = {56195235, 47411844, 25868126, 40503822, 57364, 58321048, 30416477, 31930572, 57760639, 10749657};
    private static final int[] C_D2 = {45281625, 27714825, 18181821, 13898781, 114729, 49533232, 60832955, 30306712, 48412415, 4722099};

    // Base point B and the two SPAKE2 mask points.
    private static final Point B = decodeOrThrow(hexToBytes("5866666666666666666666666666666666666666666666666666666666666666"));
    private static final Point M = derivePoint(SEED_M);
    private static final Point N = derivePoint(SEED_N);

    private final Role role;
    private final byte[] myName;
    private final byte[] theirName;
    private final byte[] privateKey = new byte[32];
    private final byte[] passwordScalar = new byte[32];
    private final byte[] passwordHash = new byte[64];
    private final byte[] myMsg = new byte[32];

    private boolean msgGenerated;
    private boolean destroyed;

    Spake2(Role role, byte[] myName, byte[] theirName) {
        this.role = role;
        this.myName = myName.clone();
        this.theirName = theirName.clone();
    }

    byte[] generateMessage(byte[] password) {
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        return generateMessage(password, random);
    }

    // Package-private seam that injects the 64-byte private value (for deterministic testing).
    byte[] generateMessage(byte[] password, byte[] random64) {
        if (destroyed) throw new IllegalStateException("The context was destroyed.");
        if (msgGenerated) throw new IllegalStateException("Message already generated.");

        // private_key = (sc_reduce(random) * 8). Multiplying by the cofactor clears it later.
        scReduce(random64);
        leftShift3(random64);
        System.arraycopy(random64, 0, privateKey, 0, 32);

        Point p = scalarMult(B, privateKey);

        // password_scalar = sc_reduce(SHA-512(password)), with the bottom three bits cleared.
        byte[] hash = sha512(password);
        System.arraycopy(hash, 0, passwordHash, 0, 64);
        byte[] reduced = hash.clone();
        scReduce(reduced);
        System.arraycopy(reduced, 0, passwordScalar, 0, 32);
        clearBottomThreeBits(passwordScalar);

        // P* = P + password_scalar * (M for Alice, N for Bob).
        Point mask = scalarMult(role == Role.ALICE ? M : N, passwordScalar);
        byte[] msg = encode(add(p, mask));
        System.arraycopy(msg, 0, myMsg, 0, 32);

        msgGenerated = true;
        return myMsg.clone();
    }

    // Returns the 64-byte shared key, or null on any invalid input (safe error: no detail leaked).
    byte[] processMessage(byte[] theirMsg) {
        if (destroyed) throw new IllegalStateException("The context was destroyed.");
        if (!msgGenerated) throw new IllegalStateException("Message not generated yet.");
        if (theirMsg == null || theirMsg.length != 32) return null;

        Point qStar = decode(theirMsg);
        if (qStar == null) return null; // point not on the curve

        // Unmask the peer's value: Q = Q* - password_scalar * (N for Alice, M for Bob).
        Point peersMask = scalarMult(role == Role.ALICE ? N : M, passwordScalar);
        Point q = sub(qStar, peersMask);

        // The shared point is private_key * Q. Cofactor was cleared via the *8 on private_key.
        byte[] dhShared = encode(scalarMult(q, privateKey));

        SHA512Digest sha = new SHA512Digest();
        if (role == Role.ALICE) {
            updateWithLengthPrefix(sha, myName);
            updateWithLengthPrefix(sha, theirName);
            updateWithLengthPrefix(sha, myMsg);
            updateWithLengthPrefix(sha, theirMsg);
        } else {
            updateWithLengthPrefix(sha, theirName);
            updateWithLengthPrefix(sha, myName);
            updateWithLengthPrefix(sha, theirMsg);
            updateWithLengthPrefix(sha, myMsg);
        }
        updateWithLengthPrefix(sha, dhShared);
        updateWithLengthPrefix(sha, passwordHash);

        byte[] key = new byte[MAX_KEY_SIZE];
        sha.doFinal(key, 0);
        return key;
    }

    boolean isDestroyed() {
        return destroyed;
    }

    void destroy() {
        destroyed = true;
        Arrays.fill(myName, (byte) 0);
        Arrays.fill(theirName, (byte) 0);
        Arrays.fill(privateKey, (byte) 0);
        Arrays.fill(passwordScalar, (byte) 0);
        Arrays.fill(passwordHash, (byte) 0);
        Arrays.fill(myMsg, (byte) 0);
    }

    // ------------------------------------------------------------------
    // edwards25519 group, extended coordinates (X:Y:Z:T), x=X/Z, y=Y/Z, T=XY/Z.
    // ------------------------------------------------------------------

    static final class Point {
        final int[] x, y, z, t;

        Point() {
            x = X25519Field.create();
            y = X25519Field.create();
            z = X25519Field.create();
            t = X25519Field.create();
        }
    }

    private static Point identity() {
        Point r = new Point();
        // (0, 1, 1, 0)
        X25519Field.one(r.y);
        X25519Field.one(r.z);
        return r;
    }

    // Complete addition law for a=-1 twisted Edwards curves (Hisil et al., add-2008-hwcd-3).
    // Complete on all of edwards25519 because d is a non-square.
    static Point add(Point p1, Point p2) {
        Point r = new Point();
        int[] a = X25519Field.create();
        int[] b = X25519Field.create();
        int[] c = X25519Field.create();
        int[] d = X25519Field.create();
        int[] e = X25519Field.create();
        int[] f = X25519Field.create();
        int[] g = X25519Field.create();
        int[] h = X25519Field.create();
        int[] t1 = X25519Field.create();
        int[] t2 = X25519Field.create();

        X25519Field.sub(p1.y, p1.x, t1);   // Y1-X1
        X25519Field.sub(p2.y, p2.x, t2);   // Y2-X2
        X25519Field.mul(t1, t2, a);        // A = (Y1-X1)(Y2-X2)

        X25519Field.add(p1.y, p1.x, t1);   // Y1+X1
        X25519Field.add(p2.y, p2.x, t2);   // Y2+X2
        X25519Field.mul(t1, t2, b);        // B = (Y1+X1)(Y2+X2)

        X25519Field.mul(p1.t, C_D2, t1);   // 2d*T1
        X25519Field.mul(t1, p2.t, c);      // C = 2d*T1*T2

        X25519Field.mul(p1.z, p2.z, t1);   // Z1*Z2
        X25519Field.add(t1, t1, d);        // D = 2*Z1*Z2

        X25519Field.sub(b, a, e);          // E = B-A
        X25519Field.sub(d, c, f);          // F = D-C
        X25519Field.add(d, c, g);          // G = D+C
        X25519Field.add(b, a, h);          // H = B+A

        X25519Field.mul(e, f, r.x);        // X3 = E*F
        X25519Field.mul(g, h, r.y);        // Y3 = G*H
        X25519Field.mul(e, h, r.t);        // T3 = E*H
        X25519Field.mul(f, g, r.z);        // Z3 = F*G
        return r;
    }

    private static Point negate(Point p) {
        Point r = new Point();
        X25519Field.negate(p.x, r.x);
        X25519Field.copy(p.y, 0, r.y, 0);
        X25519Field.copy(p.z, 0, r.z, 0);
        X25519Field.negate(p.t, r.t);
        return r;
    }

    static Point sub(Point p1, Point p2) {
        return add(p1, negate(p2));
    }

    // Constant-time (in the scalar) 4-bit fixed-window scalar multiplication.
    static Point scalarMult(Point p, byte[] scalar) {
        Point[] table = new Point[16];
        table[0] = identity();
        table[1] = p;
        for (int i = 2; i < 16; i++) {
            table[i] = add(table[i - 1], p);
        }

        Point acc = identity();
        for (int nibble = 63; nibble >= 0; nibble--) {
            acc = add(acc, acc);
            acc = add(acc, acc);
            acc = add(acc, acc);
            acc = add(acc, acc);
            int idx = (scalar[nibble >> 1] >>> ((nibble & 1) * 4)) & 0xF;
            acc = add(acc, select(table, idx));
        }
        return acc;
    }

    // Constant-time table lookup: returns a copy of table[idx] using field cmov over all entries.
    private static Point select(Point[] table, int idx) {
        Point r = new Point();
        for (int i = 0; i < 16; i++) {
            int mask = ctEq(i, idx);
            X25519Field.cmov(mask, table[i].x, 0, r.x, 0);
            X25519Field.cmov(mask, table[i].y, 0, r.y, 0);
            X25519Field.cmov(mask, table[i].z, 0, r.z, 0);
            X25519Field.cmov(mask, table[i].t, 0, r.t, 0);
        }
        return r;
    }

    // 0xFFFFFFFF if a == b, else 0.
    private static int ctEq(int a, int b) {
        int d = a ^ b;
        return ~(((d | -d) >> 31));
    }

    // RFC 8032 point decompression, mirroring BouncyCastle's Ed25519.decodePointVar (no negation).
    // Returns null if the encoding is not a valid curve point.
    static Point decode(byte[] in) {
        int sign = (in[31] & 0x80) >>> 7;
        int[] y = X25519Field.create();
        X25519Field.decode255(in, y);

        int[] u = X25519Field.create();
        int[] v = X25519Field.create();
        int[] x = X25519Field.create();
        X25519Field.sqr(y, u);          // y^2
        X25519Field.mul(C_D, u, v);     // d*y^2
        X25519Field.subOne(u);          // u = y^2 - 1
        X25519Field.addOne(v);          // v = d*y^2 + 1
        if (!X25519Field.sqrtRatioVar(u, v, x)) {
            return null;
        }
        X25519Field.normalize(x);
        if (sign == 1 && X25519Field.isZeroVar(x)) {
            return null;
        }
        if (sign != (x[0] & 1)) {
            X25519Field.negate(x, x);
            X25519Field.normalize(x);
        }

        Point r = new Point();
        X25519Field.copy(x, 0, r.x, 0);
        X25519Field.copy(y, 0, r.y, 0);
        X25519Field.one(r.z);
        X25519Field.mul(x, y, r.t);
        return r;
    }

    // Canonical 32-byte compressed encoding of a point.
    static byte[] encode(Point p) {
        int[] zInv = X25519Field.create();
        int[] x = X25519Field.create();
        int[] y = X25519Field.create();
        X25519Field.inv(p.z, zInv);
        X25519Field.mul(p.x, zInv, x);
        X25519Field.mul(p.y, zInv, y);
        X25519Field.normalize(x);
        X25519Field.normalize(y);

        byte[] out = new byte[32];
        X25519Field.encode(y, out);
        out[31] |= (byte) ((x[0] & 1) << 7);
        return out;
    }

    // ------------------------------------------------------------------
    // Scalars
    // ------------------------------------------------------------------

    // Reduces a 64-byte little-endian value modulo the group order l, writing the 32-byte result
    // into s[0..31]. This is the ref10 / BoringSSL x25519_sc_reduce, ported verbatim.
    static void scReduce(byte[] s) {
        long s0 = 2097151 & load3(s, 0);
        long s1 = 2097151 & (load4(s, 2) >> 5);
        long s2 = 2097151 & (load3(s, 5) >> 2);
        long s3 = 2097151 & (load4(s, 7) >> 7);
        long s4 = 2097151 & (load4(s, 10) >> 4);
        long s5 = 2097151 & (load3(s, 13) >> 1);
        long s6 = 2097151 & (load4(s, 15) >> 6);
        long s7 = 2097151 & (load3(s, 18) >> 3);
        long s8 = 2097151 & load3(s, 21);
        long s9 = 2097151 & (load4(s, 23) >> 5);
        long s10 = 2097151 & (load3(s, 26) >> 2);
        long s11 = 2097151 & (load4(s, 28) >> 7);
        long s12 = 2097151 & (load4(s, 31) >> 4);
        long s13 = 2097151 & (load3(s, 34) >> 1);
        long s14 = 2097151 & (load4(s, 36) >> 6);
        long s15 = 2097151 & (load3(s, 39) >> 3);
        long s16 = 2097151 & load3(s, 42);
        long s17 = 2097151 & (load4(s, 44) >> 5);
        long s18 = 2097151 & (load3(s, 47) >> 2);
        long s19 = 2097151 & (load4(s, 49) >> 7);
        long s20 = 2097151 & (load4(s, 52) >> 4);
        long s21 = 2097151 & (load3(s, 55) >> 1);
        long s22 = 2097151 & (load4(s, 57) >> 6);
        long s23 = (load4(s, 60) >> 3);
        long carry0, carry1, carry2, carry3, carry4, carry5, carry6, carry7, carry8, carry9;
        long carry10, carry11, carry12, carry13, carry14, carry15, carry16;

        s11 += s23 * 666643; s12 += s23 * 470296; s13 += s23 * 654183; s14 -= s23 * 997805; s15 += s23 * 136657; s16 -= s23 * 683901; s23 = 0;
        s10 += s22 * 666643; s11 += s22 * 470296; s12 += s22 * 654183; s13 -= s22 * 997805; s14 += s22 * 136657; s15 -= s22 * 683901; s22 = 0;
        s9 += s21 * 666643; s10 += s21 * 470296; s11 += s21 * 654183; s12 -= s21 * 997805; s13 += s21 * 136657; s14 -= s21 * 683901; s21 = 0;
        s8 += s20 * 666643; s9 += s20 * 470296; s10 += s20 * 654183; s11 -= s20 * 997805; s12 += s20 * 136657; s13 -= s20 * 683901; s20 = 0;
        s7 += s19 * 666643; s8 += s19 * 470296; s9 += s19 * 654183; s10 -= s19 * 997805; s11 += s19 * 136657; s12 -= s19 * 683901; s19 = 0;
        s6 += s18 * 666643; s7 += s18 * 470296; s8 += s18 * 654183; s9 -= s18 * 997805; s10 += s18 * 136657; s11 -= s18 * 683901; s18 = 0;

        carry6 = (s6 + (1 << 20)) >> 21; s7 += carry6; s6 -= carry6 << 21;
        carry8 = (s8 + (1 << 20)) >> 21; s9 += carry8; s8 -= carry8 << 21;
        carry10 = (s10 + (1 << 20)) >> 21; s11 += carry10; s10 -= carry10 << 21;
        carry12 = (s12 + (1 << 20)) >> 21; s13 += carry12; s12 -= carry12 << 21;
        carry14 = (s14 + (1 << 20)) >> 21; s15 += carry14; s14 -= carry14 << 21;
        carry16 = (s16 + (1 << 20)) >> 21; s17 += carry16; s16 -= carry16 << 21;

        carry7 = (s7 + (1 << 20)) >> 21; s8 += carry7; s7 -= carry7 << 21;
        carry9 = (s9 + (1 << 20)) >> 21; s10 += carry9; s9 -= carry9 << 21;
        carry11 = (s11 + (1 << 20)) >> 21; s12 += carry11; s11 -= carry11 << 21;
        carry13 = (s13 + (1 << 20)) >> 21; s14 += carry13; s13 -= carry13 << 21;
        carry15 = (s15 + (1 << 20)) >> 21; s16 += carry15; s15 -= carry15 << 21;

        s5 += s17 * 666643; s6 += s17 * 470296; s7 += s17 * 654183; s8 -= s17 * 997805; s9 += s17 * 136657; s10 -= s17 * 683901; s17 = 0;
        s4 += s16 * 666643; s5 += s16 * 470296; s6 += s16 * 654183; s7 -= s16 * 997805; s8 += s16 * 136657; s9 -= s16 * 683901; s16 = 0;
        s3 += s15 * 666643; s4 += s15 * 470296; s5 += s15 * 654183; s6 -= s15 * 997805; s7 += s15 * 136657; s8 -= s15 * 683901; s15 = 0;
        s2 += s14 * 666643; s3 += s14 * 470296; s4 += s14 * 654183; s5 -= s14 * 997805; s6 += s14 * 136657; s7 -= s14 * 683901; s14 = 0;
        s1 += s13 * 666643; s2 += s13 * 470296; s3 += s13 * 654183; s4 -= s13 * 997805; s5 += s13 * 136657; s6 -= s13 * 683901; s13 = 0;
        s0 += s12 * 666643; s1 += s12 * 470296; s2 += s12 * 654183; s3 -= s12 * 997805; s4 += s12 * 136657; s5 -= s12 * 683901; s12 = 0;

        carry0 = (s0 + (1 << 20)) >> 21; s1 += carry0; s0 -= carry0 << 21;
        carry2 = (s2 + (1 << 20)) >> 21; s3 += carry2; s2 -= carry2 << 21;
        carry4 = (s4 + (1 << 20)) >> 21; s5 += carry4; s4 -= carry4 << 21;
        carry6 = (s6 + (1 << 20)) >> 21; s7 += carry6; s6 -= carry6 << 21;
        carry8 = (s8 + (1 << 20)) >> 21; s9 += carry8; s8 -= carry8 << 21;
        carry10 = (s10 + (1 << 20)) >> 21; s11 += carry10; s10 -= carry10 << 21;

        carry1 = (s1 + (1 << 20)) >> 21; s2 += carry1; s1 -= carry1 << 21;
        carry3 = (s3 + (1 << 20)) >> 21; s4 += carry3; s3 -= carry3 << 21;
        carry5 = (s5 + (1 << 20)) >> 21; s6 += carry5; s5 -= carry5 << 21;
        carry7 = (s7 + (1 << 20)) >> 21; s8 += carry7; s7 -= carry7 << 21;
        carry9 = (s9 + (1 << 20)) >> 21; s10 += carry9; s9 -= carry9 << 21;
        carry11 = (s11 + (1 << 20)) >> 21; s12 += carry11; s11 -= carry11 << 21;

        s0 += s12 * 666643; s1 += s12 * 470296; s2 += s12 * 654183; s3 -= s12 * 997805; s4 += s12 * 136657; s5 -= s12 * 683901; s12 = 0;

        carry0 = s0 >> 21; s1 += carry0; s0 -= carry0 << 21;
        carry1 = s1 >> 21; s2 += carry1; s1 -= carry1 << 21;
        carry2 = s2 >> 21; s3 += carry2; s2 -= carry2 << 21;
        carry3 = s3 >> 21; s4 += carry3; s3 -= carry3 << 21;
        carry4 = s4 >> 21; s5 += carry4; s4 -= carry4 << 21;
        carry5 = s5 >> 21; s6 += carry5; s5 -= carry5 << 21;
        carry6 = s6 >> 21; s7 += carry6; s6 -= carry6 << 21;
        carry7 = s7 >> 21; s8 += carry7; s7 -= carry7 << 21;
        carry8 = s8 >> 21; s9 += carry8; s8 -= carry8 << 21;
        carry9 = s9 >> 21; s10 += carry9; s9 -= carry9 << 21;
        carry10 = s10 >> 21; s11 += carry10; s10 -= carry10 << 21;
        carry11 = s11 >> 21; s12 += carry11; s11 -= carry11 << 21;

        s0 += s12 * 666643; s1 += s12 * 470296; s2 += s12 * 654183; s3 -= s12 * 997805; s4 += s12 * 136657; s5 -= s12 * 683901; s12 = 0;

        carry0 = s0 >> 21; s1 += carry0; s0 -= carry0 << 21;
        carry1 = s1 >> 21; s2 += carry1; s1 -= carry1 << 21;
        carry2 = s2 >> 21; s3 += carry2; s2 -= carry2 << 21;
        carry3 = s3 >> 21; s4 += carry3; s3 -= carry3 << 21;
        carry4 = s4 >> 21; s5 += carry4; s4 -= carry4 << 21;
        carry5 = s5 >> 21; s6 += carry5; s5 -= carry5 << 21;
        carry6 = s6 >> 21; s7 += carry6; s6 -= carry6 << 21;
        carry7 = s7 >> 21; s8 += carry7; s7 -= carry7 << 21;
        carry8 = s8 >> 21; s9 += carry8; s8 -= carry8 << 21;
        carry9 = s9 >> 21; s10 += carry9; s9 -= carry9 << 21;
        carry10 = s10 >> 21; s11 += carry10; s10 -= carry10 << 21;

        s[0] = (byte) s0;
        s[1] = (byte) (s0 >> 8);
        s[2] = (byte) ((s0 >> 16) | (s1 << 5));
        s[3] = (byte) (s1 >> 3);
        s[4] = (byte) (s1 >> 11);
        s[5] = (byte) ((s1 >> 19) | (s2 << 2));
        s[6] = (byte) (s2 >> 6);
        s[7] = (byte) ((s2 >> 14) | (s3 << 7));
        s[8] = (byte) (s3 >> 1);
        s[9] = (byte) (s3 >> 9);
        s[10] = (byte) ((s3 >> 17) | (s4 << 4));
        s[11] = (byte) (s4 >> 4);
        s[12] = (byte) (s4 >> 12);
        s[13] = (byte) ((s4 >> 20) | (s5 << 1));
        s[14] = (byte) (s5 >> 7);
        s[15] = (byte) ((s5 >> 15) | (s6 << 6));
        s[16] = (byte) (s6 >> 2);
        s[17] = (byte) (s6 >> 10);
        s[18] = (byte) ((s6 >> 18) | (s7 << 3));
        s[19] = (byte) (s7 >> 5);
        s[20] = (byte) (s7 >> 13);
        s[21] = (byte) s8;
        s[22] = (byte) (s8 >> 8);
        s[23] = (byte) ((s8 >> 16) | (s9 << 5));
        s[24] = (byte) (s9 >> 3);
        s[25] = (byte) (s9 >> 11);
        s[26] = (byte) ((s9 >> 19) | (s10 << 2));
        s[27] = (byte) (s10 >> 6);
        s[28] = (byte) ((s10 >> 14) | (s11 << 7));
        s[29] = (byte) (s11 >> 1);
        s[30] = (byte) (s11 >> 9);
        s[31] = (byte) (s11 >> 17);
    }

    // Multiplies a 256-bit little-endian value (s[0..31]) by 8, in place.
    static void leftShift3(byte[] s) {
        int carry = 0;
        for (int i = 0; i < 32; i++) {
            int nextCarry = (s[i] & 0xFF) >>> 5;
            s[i] = (byte) ((s[i] << 3) | carry);
            carry = nextCarry;
        }
    }

    // Clears the bottom three bits of a 32-byte scalar (mod l) by conditionally adding l, 2l, 4l,
    // so the scalar becomes a multiple of the cofactor. Constant-time. Matches BoringSSL.
    static void clearBottomThreeBits(byte[] scalar) {
        byte[] order = L.clone();
        byte[] tmp = new byte[32];

        scalarCmov(tmp, order, ctEqByte(scalar[0] & 1, 1));
        scalarAdd(scalar, tmp);

        scalarDouble(order);
        Arrays.fill(tmp, (byte) 0);
        scalarCmov(tmp, order, ctEqByte(scalar[0] & 2, 2));
        scalarAdd(scalar, tmp);

        scalarDouble(order);
        Arrays.fill(tmp, (byte) 0);
        scalarCmov(tmp, order, ctEqByte(scalar[0] & 4, 4));
        scalarAdd(scalar, tmp);
    }

    // dst = mask==-1 ? src : dst, constant-time, per byte.
    private static void scalarCmov(byte[] dst, byte[] src, int mask) {
        byte m = (byte) mask;
        for (int i = 0; i < 32; i++) {
            dst[i] = (byte) ((m & src[i]) | (~m & dst[i]));
        }
    }

    private static void scalarDouble(byte[] s) {
        int carry = 0;
        for (int i = 0; i < 32; i++) {
            int out = (s[i] & 0xFF) >>> 7;
            s[i] = (byte) ((s[i] << 1) | carry);
            carry = out;
        }
    }

    private static void scalarAdd(byte[] s, byte[] add) {
        int carry = 0;
        for (int i = 0; i < 32; i++) {
            int sum = (s[i] & 0xFF) + (add[i] & 0xFF) + carry;
            s[i] = (byte) sum;
            carry = sum >>> 8;
        }
    }

    private static int ctEqByte(int a, int b) {
        int d = a ^ b;
        return ~(((d | -d) >> 31));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Point derivePoint(String seed) {
        SHA256Digest sha = new SHA256Digest();
        byte[] s;
        try {
            s = seed.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
        sha.update(s, 0, s.length);
        byte[] enc = new byte[32];
        sha.doFinal(enc, 0);
        return decodeOrThrow(enc);
    }

    private static Point decodeOrThrow(byte[] enc) {
        Point p = decode(enc);
        if (p == null) throw new IllegalStateException("Invalid hard-coded curve point.");
        return p;
    }

    private static byte[] sha512(byte[] in) {
        SHA512Digest sha = new SHA512Digest();
        sha.update(in, 0, in.length);
        byte[] out = new byte[64];
        sha.doFinal(out, 0);
        return out;
    }

    private static void updateWithLengthPrefix(SHA512Digest sha, byte[] data) {
        byte[] lenLe = new byte[8];
        long len = data.length;
        for (int i = 0; i < 8; i++) {
            lenLe[i] = (byte) (len & 0xFF);
            len >>>= 8;
        }
        sha.update(lenLe, 0, 8);
        sha.update(data, 0, data.length);
    }

    private static long load3(byte[] in, int o) {
        return (in[o] & 0xFFL) | ((in[o + 1] & 0xFFL) << 8) | ((in[o + 2] & 0xFFL) << 16);
    }

    private static long load4(byte[] in, int o) {
        return (in[o] & 0xFFL) | ((in[o + 1] & 0xFFL) << 8) | ((in[o + 2] & 0xFFL) << 16) | ((in[o + 3] & 0xFFL) << 24);
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
