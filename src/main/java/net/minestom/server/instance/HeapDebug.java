package net.minestom.server.instance;

import javax.management.MBeanServer;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Instant;

import com.sun.management.HotSpotDiagnosticMXBean;

public class HeapDebug {
    public static void dumpHeap() throws IOException {
        var file = new File("heaps");
        if (!file.isDirectory()) file.mkdir();

        System.gc();
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        HotSpotDiagnosticMXBean mxBean = ManagementFactory.newPlatformMXBeanProxy(
                server, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class);
        mxBean.dumpHeap(new File(file, Instant.now().getEpochSecond() + ".hprof").getPath(), true);
    }
}
