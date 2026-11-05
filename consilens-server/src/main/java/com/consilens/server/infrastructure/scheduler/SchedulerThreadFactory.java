package com.consilens.server.infrastructure.scheduler;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

class SchedulerThreadFactory implements ThreadFactory {

    private final AtomicInteger sequence = new AtomicInteger(1);
    private final String namePrefix;

    SchedulerThreadFactory(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, namePrefix + sequence.getAndIncrement());
        thread.setDaemon(false);
        return thread;
    }
}
