package io.github.hejcz;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public final class TestExecutor {
    private final ThreadLocal<Context> threadGraalContext;
    private final ScheduledExecutorService timeoutGuard = Executors.newSingleThreadScheduledExecutor();
    
    TestExecutor() {
        final Engine engine = Engine.newBuilder()
                .build();
        final HostAccess hostAccess = hostAccessPolicy();
        this.threadGraalContext = ThreadLocal.withInitial(
                () -> createNewContext(engine, hostAccess));
    }

    public Object eval(final String script) {
        return run(script, Collections.emptySortedSet());
    }

    private static Context createNewContext(Engine engine, HostAccess hostAccess) {
        return Context.newBuilder("js")
                .engine(engine)
                .allowCreateProcess(false)
                .allowCreateThread(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowHostClassLoading(false)
                .allowHostClassLookup(null)
                .allowIO(false)
                .allowNativeAccess(false)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowHostAccess(hostAccess)
                .option("js.strict", "true")
                .allowExperimentalOptions(true)
                .option("js.interop-complete-promises", "true")
                .option("js.console", "false")
                .option("js.foreign-object-prototype", "true")
                .option("js.graal-builtin", "false")
                .option("js.regexp-static-result", "false")
                .option("js.java-package-globals", "false")
                .option("js.global-property", "false")
                .option("js.performance", "false")
                .option("js.print", "false")
                .option("js.load", "false")
                .option("js.disable-eval", "true")
                .resourceLimits(null)
                .build();
    }

    private static HostAccess hostAccessPolicy() {
        return HostAccess.newBuilder()
                .allowAccessAnnotatedBy(HostAccess.Export.class)
                .allowListAccess(true)
                .build();
    }

    private Object run(final String preparedScript, final Collection<Object> functionArgs) {
        final Context context = threadGraalContext.get();
        try {
            try {
                return runScriptWithTimeout(preparedScript, functionArgs, context);
            } catch (PolyglotException | IllegalStateException e) {
                threadGraalContext.remove();
                // statements limit
                if (e.getMessage().startsWith("Statement count limit of")) {
                    throw new RuntimeException(e.getMessage());
                }
                throw new RuntimeException(e.getMessage(), e);
            }
        } finally {
            final Context oldOrNewContext = threadGraalContext.get();
            oldOrNewContext.resetLimits();
        }
    }

    private Value runScriptWithTimeout(String script, Collection<Object> functionArgs,
            Context context) {
        final ScheduledFuture<?> timeoutFuture = timeoutGuard.schedule(
                () -> context.close(true), 20, TimeUnit.MILLISECONDS);
        try {
            final Value result = runScript(script, functionArgs, context);
            final boolean cancelled = timeoutFuture.cancel(false);
            if (!cancelled) {
                threadGraalContext.remove();
            }
            return result;
        } catch (PolyglotException | IllegalStateException e) {
            timeoutFuture.cancel(false);
            threadGraalContext.remove();
            // timeout
            if (e.getMessage().startsWith("Execution got cancelled.")
                    || e.getMessage().startsWith("Thread was interrupted.")
                    // Context closed between eval and execute
                    || e.getMessage().startsWith("The Context is already closed.")
                    // Context is closing when execute is called
                    || e.getMessage().startsWith("Multi threaded access requested")) {
                throw new RuntimeException("evaluation exceeded time limits");
            }
            throw e;
        }
    }

    private Value runScript(String script, Collection<Object> functionArgs, Context context) {
        final Value function = context.eval(Source.create("js", script));
        return function.execute(functionArgs.toArray());
    }

}
