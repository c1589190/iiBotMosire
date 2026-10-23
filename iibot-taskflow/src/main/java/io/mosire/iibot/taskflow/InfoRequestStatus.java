package io.mosire.iibot.taskflow;

/** 信息请求的生命周期；已发出的请求不会被重复答复覆盖。 */
public enum InfoRequestStatus {
    REQUESTED,
    ISSUED,
    EXPIRED
}
