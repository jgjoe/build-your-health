package io.github.jgjoe.byh.auth;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("byh.LoginWork")
@Label("Login work phases without account identifiers")
@Category({"BYH", "Authentication"})
@Enabled(false)
@StackTrace(false)
public final class LoginWorkEvent extends Event {
    public String outcome = "error";
    public long accountReadNanos;
    public long lookupNanos;
    public long bcryptNanos;
    public long accountWriteNanos;
}
