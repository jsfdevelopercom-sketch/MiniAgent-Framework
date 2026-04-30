package com.miniagent.core;

/**
 * Controls how a direct single-model entity behaves.
 */
public enum ModelEntityMode {

    /**
     * User is talking directly to this one model.
     * Response should be useful, complete, and not artificially silent.
     */
    SINGLE_MODEL_EXCLUSIVE,

    /**
     * Model is one speaker inside a group discussion.
     * Response should be short, differentiated, and can return [SILENCE].
     */
    GROUP_CHAT_MEMBER
}