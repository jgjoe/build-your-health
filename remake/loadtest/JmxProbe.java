import java.time.Instant;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

// Measurement-only JDK client: no application instrumentation/dependencies.
class JmxProbe {
    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(args[0]);
        try (var connector = JMXConnectorFactory.connect(new JMXServiceURL(
                "service:jmx:rmi:///jndi/rmi://app:9010/jmxrmi"))) {
            var server = connector.getMBeanServerConnection();
            var names = server.queryNames(new ObjectName("com.zaxxer.hikari:type=Pool (*)"), null);
            if (names.size() != 1) throw new IllegalStateException("Expected one Hikari pool: " + names);
            var pool = names.iterator().next();
            System.out.println("utc,active,idle,total,awaiting");
            for (int i = 0; i < seconds; i++) {
                System.out.printf("%s,%s,%s,%s,%s%n", Instant.now(),
                        server.getAttribute(pool, "ActiveConnections"),
                        server.getAttribute(pool, "IdleConnections"),
                        server.getAttribute(pool, "TotalConnections"),
                        server.getAttribute(pool, "ThreadsAwaitingConnection"));
                Thread.sleep(1000);
            }
        }
    }
}
