package xyz.yychainsaw.backend.service;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.Semaphore;

/** Single-process coordination between upload I/O and garbage collection. */
public final class StorageOperations {
    public static final ReentrantReadWriteLock LOCK=new ReentrantReadWriteLock(true);
    static final Semaphore WRITERS=new Semaphore(4,true);
    static final Semaphore MERGES=new Semaphore(1,true);
    private StorageOperations() {}
}
