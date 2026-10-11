package com.darchan.diag.run;

/**
 * Thrown by {@link Debugger} when the target throws while executing. The message includes the diagnostic report
 * (recent trace, register dump and configured memory dumps); the original exception is the cause.
 */
public final class ExecutionFailure extends RuntimeException {

    private final String report;

    public ExecutionFailure(String summary, String report, Throwable cause) {
        super(summary + "\n" + report, cause);
        this.report = report;
    }

    /** the diagnostic report alone */
    public String report() {
        return report;
    }
}
