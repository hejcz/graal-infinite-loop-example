package io.github.hejcz;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;

class InfinityLoopTest {

    @RepeatedTest(100)
    void shouldHandleTimeout() {
        final TestExecutor testExecutor = new TestExecutor();
        final RuntimeException exception = Assertions.assertThrows(
                RuntimeException.class, () -> testExecutor.eval("(function() { while (true); })"));
        Assertions.assertEquals("evaluation exceeded time limits", exception.getMessage());
    }

}
