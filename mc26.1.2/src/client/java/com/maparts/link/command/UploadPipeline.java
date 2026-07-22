package com.maparts.link.command;

import com.maparts.link.capture.MapGridCapture;
import com.maparts.link.http.BackendClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The draft -> upload -> finalize chain shared by /mapupload and
 * /mapuploadall. When the site already stores this exact image (anyone's,
 * any status), the draft response says so and the uploader is simply added
 * as an owner of the existing mapart — the upload/finalize steps are skipped
 * entirely.
 */
final class UploadPipeline {
    private static final long RETRY_DELAY_SECONDS = 5;

    /** Terminal outcome of one upload. */
    record Outcome(boolean success, String message, String url, String urlLabel) {
        static Outcome ok(String mapartId) {
            return new Outcome(true, null,
                    LinkCommand.BACKEND_URL + "/maparts/" + mapartId,
                    "Open it on the site");
        }

        static Outcome owned(String mapartId, boolean alreadyOwned) {
            return new Outcome(true,
                    alreadyOwned
                            ? "You already own this mapart."
                            : "This mapart already exists — you've been added as an owner.",
                    LinkCommand.BACKEND_URL + "/maparts/" + mapartId,
                    "Open it on the site");
        }

        static Outcome fail(String message) {
            return new Outcome(false, message, null, null);
        }
    }

    private UploadPipeline() {}

    /**
     * Runs the upload chain, retrying exactly once — after a 5s delay — if
     * the first attempt fails. onRetrying (if not null) fires the moment a
     * retry is scheduled, so the caller can let the player know why nothing
     * seems to be happening.
     */
    static CompletableFuture<Outcome> upload(
            BackendClient backend, String token, String title,
            MapGridCapture.Result capture, Runnable onRetrying) {
        return attempt(backend, token, title, capture)
                .thenCompose(outcome -> {
                    if (outcome.success()) {
                        return CompletableFuture.completedFuture(outcome);
                    }

                    if (onRetrying != null) {
                        onRetrying.run();
                    }

                    return afterDelay()
                            .thenCompose(ignored -> attempt(backend, token, title, capture));
                });
    }

    private static CompletableFuture<Void> afterDelay() {
        return CompletableFuture.runAsync(
                () -> {},
                CompletableFuture.delayedExecutor(RETRY_DELAY_SECONDS, TimeUnit.SECONDS));
    }

    private static CompletableFuture<Outcome> attempt(
            BackendClient backend, String token, String title,
            MapGridCapture.Result capture) {
        return backend
                .createDraft(token, title, capture.mapsWide(), capture.mapsTall(),
                        capture.png(), capture.tiles())
                .thenCompose(draft -> {
                    if (!draft.success()) {
                        return CompletableFuture.completedFuture(
                                Outcome.fail(draft.message()));
                    }

                    if (draft.owned()) {
                        return CompletableFuture.completedFuture(
                                Outcome.owned(draft.mapartId(), draft.alreadyOwned()));
                    }

                    return backend.uploadPng(draft.uploadUrl(), capture.png())
                            .thenCompose(upload -> {
                                if (!upload.success()) {
                                    return CompletableFuture.completedFuture(
                                            Outcome.fail(upload.message()));
                                }

                                return backend
                                        .finalizeDraft(token, draft.mapartId())
                                        .thenApply(result -> {
                                            if (!result.success()) {
                                                return Outcome.fail(result.message());
                                            }

                                            if (result.owned()) {
                                                return Outcome.owned(
                                                        result.mapartId(),
                                                        result.alreadyOwned());
                                            }

                                            return Outcome.ok(
                                                    result.mapartId() != null
                                                            ? result.mapartId()
                                                            : draft.mapartId());
                                        });
                            });
                });
    }
}



