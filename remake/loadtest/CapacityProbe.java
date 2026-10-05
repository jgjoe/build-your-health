import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Instant;
import java.util.HashMap;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

// Read-only observer. Stack samples indicate location, not exact CPU attribution.
class CapacityProbe {
    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(args[0]);
        try (var connector = JMXConnectorFactory.connect(new JMXServiceURL(
                "service:jmx:rmi:///jndi/rmi://app:9010/jmxrmi"))) {
            var server = connector.getMBeanServerConnection();
            var names = server.queryNames(new ObjectName("com.zaxxer.hikari:type=Pool (*)"), null);
            if (names.size() != 1) throw new IllegalStateException("Expected one pool: " + names);
            var pool = names.iterator().next();
            var os = new ObjectName("java.lang:type=OperatingSystem");
            var threads = ManagementFactory.newPlatformMXBeanProxy(server,
                    ManagementFactory.THREAD_MXBEAN_NAME, com.sun.management.ThreadMXBean.class);
            if (!threads.isThreadCpuTimeSupported() || !threads.isThreadCpuTimeEnabled())
                throw new IllegalStateException("Thread CPU counters unavailable");
            var previous = new HashMap<Long, Long>();
            System.out.println("utc,active,idle,total,awaiting,process_cpu_ns,http_cpu_delta_ns,http_runnable,bcrypt_runnable");
            for (int i = 0; i < seconds; i++) {
                long[] ids = threads.getAllThreadIds();
                ThreadInfo[] infos = threads.getThreadInfo(ids, 32);
                long[] cpu = threads.getThreadCpuTime(ids);
                long httpCpu = 0;
                int httpRunnable = 0, bcryptRunnable = 0;
                for (int j = 0; j < ids.length; j++) {
                    Long old = previous.put(ids[j], cpu[j]);
                    ThreadInfo info = infos[j];
                    if (info == null || !info.getThreadName().startsWith("http-nio-")) continue;
                    if (old != null && old >= 0 && cpu[j] >= old) httpCpu += cpu[j] - old;
                    if (info.getThreadState() != Thread.State.RUNNABLE) continue;
                    httpRunnable++;
                    for (var frame : info.getStackTrace()) {
                        if (frame.getClassName().contains("BCrypt")) {
                            bcryptRunnable++;
                            break;
                        }
                    }
                }
                System.out.printf("%s,%s,%s,%s,%s,%s,%d,%d,%d%n", Instant.now(),
                        server.getAttribute(pool, "ActiveConnections"), server.getAttribute(pool, "IdleConnections"),
                        server.getAttribute(pool, "TotalConnections"), server.getAttribute(pool, "ThreadsAwaitingConnection"),
                        server.getAttribute(os, "ProcessCpuTime"), httpCpu, httpRunnable, bcryptRunnable);
                Thread.sleep(1000);
            }
        }
    }
}
