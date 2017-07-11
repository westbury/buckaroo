package com.loopperfect.buckaroo;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.loopperfect.buckaroo.cli.CLICommand;
import com.loopperfect.buckaroo.cli.CLIParsers;
import com.loopperfect.buckaroo.sources.RecipeNotFoundException;
import com.loopperfect.buckaroo.tasks.LoggingTasks;
import com.loopperfect.buckaroo.views.GenericEventRenderer;
import com.loopperfect.buckaroo.views.ProgressView;
import com.loopperfect.buckaroo.views.StatsView;
import com.loopperfect.buckaroo.views.SummaryView;
import com.loopperfect.buckaroo.virtualterminal.Color;
import com.loopperfect.buckaroo.virtualterminal.TerminalBuffer;
import com.loopperfect.buckaroo.virtualterminal.components.Component;
import com.loopperfect.buckaroo.virtualterminal.components.ListLayout;
import com.loopperfect.buckaroo.virtualterminal.components.StackLayout;
import com.loopperfect.buckaroo.virtualterminal.components.Text;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import org.fusesource.jansi.AnsiConsole;
import org.jparsec.Parser;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.loopperfect.buckaroo.ErrorHandler.handleErrors;

public final class Main {

    private static final int TERMINAL_WIDTH = 80;

    private Main() {}

    public static void main(final String[] args) {

        if (args.length == 0) {
            System.out.println("Buck, Buck, Buckaroo! \uD83E\uDD20");
            System.out.println("https://buckaroo.readthedocs.io/");
            return;
        }

        final FileSystem fs = FileSystems.getDefault();

        final String rawCommand = String.join(" ", args);

        final CountDownLatch loggingLatch = new CountDownLatch(1);
        final CountDownLatch taskLatch = new CountDownLatch(1);

        // Send the command to the logging server, if present
        LoggingTasks.log(fs, rawCommand).subscribe(
            next -> {
                // Do nothing
            },
            error -> {
                // Do nothing
                loggingLatch.countDown();
            },
            () -> {
                // Do nothing
                loggingLatch.countDown();
            });

        // Parse the command
        final Parser<CLICommand> commandParser = CLIParsers.commandParser;

        try {
            final CLICommand command = commandParser.parse(rawCommand);

            final Observable<Event> task = command.routine().apply(fs);

            final Observable<Either<Throwable, Event>> errorOrEvent = task
                .map(Either::<Throwable, Event>right)
                .onErrorReturn(Either::<Throwable, Event>left);

            final Observable<Component> components = errorOrEvent
                .publish(upstream -> {
                        Observable<Component> errors  = upstream.filter(Either::isLeft).map(x->x.left().get()).cache()
                            .take(1).flatMap(Observable::error).cast(Component.class);

                        return upstream.takeUntil(errors).filter(Either::isRight).map(e -> e.right().get()).compose(u ->
                            Observable.combineLatest(
                                    ProgressView.render(u)
                                        .startWith(StackLayout.of())
                                        .subscribeOn(Schedulers.computation())
                                        .concatWith(Observable.just(StackLayout.of())),
                                    StatsView.render(u)
                                        .subscribeOn(Schedulers.computation())
                                        .skip(300, TimeUnit.MILLISECONDS)
                                        .takeUntil(upstream.lastElement().toObservable())
                                        .startWith(StackLayout.of()),
                                    SummaryView.render(u)
                                        .takeLast(1)
                                        .startWith(StackLayout.of()),
                                (x, y, z) -> (Component) StackLayout.of(x, y, z))

                        );
                })
                .subscribeOn(Schedulers.computation())
                .sample(100, TimeUnit.MILLISECONDS, true)
                .distinctUntilChanged()
                .doOnNext(x->{ System.gc(); });

            AnsiConsole.systemInstall();

            final TerminalBuffer buffer = new TerminalBuffer();

            components
                .map(x -> x.render(TERMINAL_WIDTH))
                .subscribe(
                    buffer::flip,
                    error -> {
                        taskLatch.countDown();
                        handleErrors(error, buffer, fs);
                    },
                    () -> {
                        taskLatch.countDown();
                    });

            taskLatch.await();
            loggingLatch.await(1000L, TimeUnit.MILLISECONDS);

        } catch (final Throwable e) {
            System.out.println("Uh oh!");
            System.out.println(e.getMessage());
        }
    }
}
