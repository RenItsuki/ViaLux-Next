package com.visionguide.phone;

final class Guidance {
    static final int PRIORITY_INFO = 1;
    static final int PRIORITY_IMPORTANT = 2;
    static final int PRIORITY_URGENT = 3;

    final String message;
    final boolean emergency;
    final Detection detection;
    final boolean moving;
    final int priority;

    Guidance(String message, boolean emergency, Detection detection, boolean moving, int priority) {
        this.message = message;
        this.emergency = emergency;
        this.detection = detection;
        this.moving = moving;
        this.priority = priority;
    }
}
