package com.example.audioseparator;

public interface ProgressListener {
    void progressChanged(int current, int total);
    void progressPublish(String info);
    void progressDone();
    void progressError();
}
