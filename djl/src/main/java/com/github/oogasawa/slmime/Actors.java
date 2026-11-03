package com.github.oogasawa.slmime;

import com.scivicslab.pojoactor.core.ActorRef;

import java.util.concurrent.CompletionException;

/** Asking an actor for an answer and waiting for it on the thread that asks. */
final class Actors {

    private Actors() {
    }

    /** Work done by an actor on its object; it may throw. */
    @FunctionalInterface
    interface Work<T, R> {
        R apply(T target) throws Exception;
    }

    /** What the actor answers; an exception thrown by the work is thrown here (a checked one wrapped). */
    static <T, R> R ask(ActorRef<T> actor, Work<T, R> work) {
        try {
            return actor.ask(t -> {
                try {
                    return work.apply(t);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw cause instanceof RuntimeException r ? r : new IllegalStateException(cause);
        }
    }
}
