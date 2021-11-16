package dev.rollczi.litecommands.component;

import dev.rollczi.litecommands.LiteInvocation;
import dev.rollczi.litecommands.LiteSender;
import dev.rollczi.litecommands.annotations.parser.AnnotationParser;
import dev.rollczi.litecommands.inject.InjectContext;
import dev.rollczi.litecommands.valid.Valid;
import dev.rollczi.litecommands.valid.ValidationCommandException;
import dev.rollczi.litecommands.valid.ValidationInfo;
import org.slf4j.Logger;
import panda.std.stream.PandaStream;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LiteExecution extends AbstractComponent {

    private final Logger logger;
    private final AnnotationParser parser;
    private final MethodExecutor executor;

    LiteExecution(Logger logger, AnnotationParser parser, ScopeMetaData scopeMetaData, MethodExecutor executor) {
        super(scopeMetaData);
        this.logger = logger;
        this.parser = parser;
        this.executor = executor;
    }

    @Override
    public void resolveExecution(MetaData data) {
        LiteInvocation invocation = data.getInvocation();
        LiteSender sender = invocation.sender();

        Set<String> permissions = new HashSet<>(this.scope.getPermissions());

        PandaStream.of(data.getTracesOfResolvers()).map(LiteComponent::getScope).concat(this.scope).forEach(scope -> {
            permissions.addAll(scope.getPermissions());
            permissions.removeAll(scope.getPermissionsExclude());
        });

        for (String permission : permissions) {
            Valid.whenWithContext(!sender.hasPermission(permission), ValidationInfo.NO_PERMISSION, data, this);
        }

        Valid.whenWithContext(!scope.getArgsValidator().valid(data.getCurrentArgsCount(this)), ValidationInfo.INVALID_USE, data, this);

        executor.execute(new InjectContext(data, this)).onError(error -> {
            if (error.getSecond() instanceof ValidationCommandException exception) {
                Valid.whenWithContext(exception.getMessage() == null, exception.getValidationInfo(), data, this);

                throw exception;
            }

            logger.error(error.getFirst(), error.getSecond());
        });
    }

    @Override
    public List<String> resolveCompletion(MetaData data) {
        return getExecutorCompletion(data, data.getCurrentArgsCount(this) - 1);
    }

    public List<String> getExecutorCompletion(MetaData data, int argNumber) {
        LiteInvocation invocation = data.getInvocation();

        return executor.getParameter(argNumber)
                .flatMap(parser::getArgumentHandler)
                .map(argumentHandler -> argumentHandler.tabulation(invocation.name(), invocation.arguments()))
                .orElseGet(Collections.emptyList());
    }
}
