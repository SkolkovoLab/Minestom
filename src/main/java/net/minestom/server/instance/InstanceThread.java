package net.minestom.server.instance;

import net.minestom.server.MinecraftServer;

public class InstanceThread extends Thread {
    private final Instance instance;

    public InstanceThread(Instance instance) {
        super("Instance Thread " + instance.getNumber());
        this.instance = instance;
    }

    @Override
    public void run() {
        while (instance.isRegistered()) {
            var tickStart = System.currentTimeMillis();
            instance.threadLoop(tickStart);
            instance.tickTime = (int) (System.currentTimeMillis() - tickStart);
            var sleepTime = MinecraftServer.TICK_MS - instance.tickTime;
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
