package dev.tommy.foldshell;
interface IFoldEngine {
    void destroy() = 16777114;
    String start(IBinder owner, float intensity) = 1;
    String status() = 2;
    void stop() = 3;
}
