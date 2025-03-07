package net.minestom.server.instance;

import net.minestom.server.MinecraftServer;

public class InstanceThread extends Thread {
    private final Instance instance;
    private final long startTime = System.currentTimeMillis();
    private long tickCounter = 0;

    public InstanceThread(Instance instance) {
        super("Instance Thread " + instance.getNumber());
        this.instance = instance;
    }

    @Override
    public void run() {
        while (instance.isRegistered()) {
            var tickStart = System.currentTimeMillis();
            instance.threadLoop(tickStart);
            tickCounter++;
            var tickEnd = System.currentTimeMillis();
            instance.tickTime = (int) (tickEnd - tickStart);

            var nextTickStart = startTime + (tickCounter * MinecraftServer.TICK_MS);

            var sleepTime = nextTickStart - tickEnd;
            if (sleepTime > 0) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    public Instance getInstance() {
        return instance;
    }
}
