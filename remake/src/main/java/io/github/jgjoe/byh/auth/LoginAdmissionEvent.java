package io.github.jgjoe.byh.auth;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("byh.LoginAdmission")
@Label("Login permit wait and service")
@Category({"BYH", "Authentication"})
@Enabled(false)
@StackTrace(false)
public final class LoginAdmissionEvent extends Event {
    public String decision;
    public long waitNanos;
    public long serviceNanos;
    public long waitBudgetMillis;
}
