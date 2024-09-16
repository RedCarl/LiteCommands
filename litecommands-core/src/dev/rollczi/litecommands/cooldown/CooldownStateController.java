package dev.rollczi.litecommands.cooldown;

import dev.rollczi.litecommands.command.executor.CommandExecuteResult;
import dev.rollczi.litecommands.command.executor.event.CommandExecutorEvent;
import dev.rollczi.litecommands.command.executor.event.CommandPostExecutionEvent;
import dev.rollczi.litecommands.command.executor.event.CommandPreExecutionEvent;
import dev.rollczi.litecommands.event.EventListener;
import dev.rollczi.litecommands.event.Subscriber;
import dev.rollczi.litecommands.invocation.Invocation;
import dev.rollczi.litecommands.meta.Meta;
import dev.rollczi.litecommands.platform.PlatformSender;
import dev.rollczi.litecommands.scheduler.Scheduler;
import dev.rollczi.litecommands.scheduler.SchedulerPoll;
import dev.rollczi.litecommands.shared.FailedReason;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public class CooldownStateController<SENDER> implements EventListener {

    private final Scheduler scheduler;
    private final Map<CooldownCompositeKey, Instant> cooldowns = new HashMap<>();

    public CooldownStateController(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Subscriber
    public void onEvent(CommandPreExecutionEvent<SENDER> event) {
        Invocation<SENDER> invocation = event.getInvocation();
        PlatformSender sender = invocation.platformSender();
        CooldownContext cooldownContext = getOperativeContext(event, sender);
        
        if (cooldownContext == null) {
            return;
        }

        CooldownCompositeKey compositeKey = new CooldownCompositeKey(sender.getIdentifier(), cooldownContext.getKey());

        Instant now = Instant.now();
        Instant expirationTime = cooldowns.get(compositeKey);

        if (expirationTime != null && expirationTime.isAfter(now)) {
            event.stopFlow(FailedReason.of(new CooldownState(cooldownContext, Duration.between(now, expirationTime), expirationTime)));
        }
    }

    @Subscriber
    public void onEvent(CommandPostExecutionEvent<SENDER> event) {
        CommandExecuteResult result = event.getResult();

        if (!result.isSuccessful()) {
            return;
        }

        Invocation<SENDER> invocation = event.getInvocation();
        PlatformSender sender = invocation.platformSender();
        CooldownContext cooldownContext = getOperativeContext(event, sender);

        if (cooldownContext == null) {
            return;
        }

        CooldownCompositeKey compositeKey = new CooldownCompositeKey(sender.getIdentifier(), cooldownContext.getKey());

        Instant now = Instant.now();
        cooldowns.put(compositeKey, now.plus(cooldownContext.getDuration()));
        scheduler.supplyLater(SchedulerPoll.MAIN, cooldownContext.getDuration(), () -> cooldowns.remove(compositeKey));
    }

    @Nullable
    private CooldownContext getOperativeContext(CommandExecutorEvent event, PlatformSender sender) {
        List<CooldownContext> cooldownContexts = event.getExecutor().metaCollector().collect(Meta.COOLDOWN);

        if (cooldownContexts.isEmpty()) {
            return null;
        }

        CooldownContext cooldownContext = cooldownContexts.get(0);
        String bypassPermission = cooldownContext.getBypassPermission();

        if (!bypassPermission.isEmpty() && sender.hasPermission(bypassPermission)) {
            return null;
        }

        return cooldownContext;
    }

}