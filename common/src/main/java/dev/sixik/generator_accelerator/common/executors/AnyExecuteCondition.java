package dev.sixik.generator_accelerator.common.executors;

import java.util.function.Function;

public class AnyExecuteCondition<T> implements ExecuteCondition{

    protected T server;
    protected Function<T, Boolean> function;

    public AnyExecuteCondition(T server, Function<T, Boolean> function) {
        this.function = function;
        this.server = server;
    }

    @Override
    public boolean canExecuteTask() {
        return function.apply(server);
    }
}
