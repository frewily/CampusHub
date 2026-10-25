package io.github.frewily.campushub.identity.application;

/** Revokes one normalized token, then clears the current execution's legacy identity. */
public interface SessionTerminator {
    void terminate(String token);
}
