package com.education.agent.common.api;

public record SimpleData(boolean success) {
    public static SimpleData succeeded() {
        return new SimpleData(true);
    }
}
