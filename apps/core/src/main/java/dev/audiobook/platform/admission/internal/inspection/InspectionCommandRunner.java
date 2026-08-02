package dev.audiobook.platform.admission.internal.inspection;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class InspectionCommandRunner {

    Result run(List<String> command, Duration timeout) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyProcessTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
                return Result.timeoutResult();
            }
            return Result.completed(process.exitValue());
        } catch (IOException exception) {
            return Result.failureResult();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                destroyProcessTree(process);
            }
            return Result.timeoutResult();
        }
    }

    private static void destroyProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    record Result(Integer exitCode, boolean timedOut, boolean failed) {
        static Result completed(int exitCode) {
            return new Result(exitCode, false, false);
        }

        static Result timeoutResult() {
            return new Result(null, true, false);
        }

        static Result failureResult() {
            return new Result(null, false, true);
        }
    }
}
