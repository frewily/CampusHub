package io.github.frewily.campushub.utils;

public interface ILock {

    boolean tryLock(Long timeoutSec);

    void unLock();
}
