package dev.audiobook.platform.retention.restore.service;

public interface TombstoneReplayService {

    ReplayReport replay();

    record ReplayReport(int tombstonesChecked, int referencesDenied, int requestsReissued) {}
}
