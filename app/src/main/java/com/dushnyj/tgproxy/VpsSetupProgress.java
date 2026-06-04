package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VpsSetupProgress {
    enum Stage {
        AUDIT,
        PLAN,
        BACKUP,
        INSTALL,
        VERIFY,
        ROLLBACK,
        SAVE
    }

    private final Stage stage;
    private final int percent;
    private final String message;

    private VpsSetupProgress(Stage stage, int percent, String message) {
        this.stage = stage == null ? Stage.AUDIT : stage;
        this.percent = clamp(percent);
        this.message = message == null ? "" : message.trim();
    }

    static VpsSetupProgress of(Stage stage, int percent, String message) {
        return new VpsSetupProgress(stage, percent, message);
    }

    static List<VpsSetupProgress> defaultSteps() {
        ArrayList<VpsSetupProgress> steps = new ArrayList<>();
        steps.add(of(Stage.AUDIT, 5, "Выполняется read-only аудит VPS"));
        steps.add(of(Stage.PLAN, 25, "Подготовка плана изменений"));
        steps.add(of(Stage.BACKUP, 45, "Создание backup перед изменениями"));
        steps.add(of(Stage.INSTALL, 70, "Установка tgproxy-relay и systemd unit"));
        steps.add(of(Stage.VERIFY, 90, "Проверка /healthz, /version и /test-routes"));
        steps.add(of(Stage.SAVE, 100, "Relay проверен и сохранён в профиле"));
        return Collections.unmodifiableList(steps);
    }

    Stage stage() {
        return stage;
    }

    int percent() {
        return percent;
    }

    String message() {
        return message;
    }

    String statusLine() {
        return percent + "% • " + message;
    }

    private static int clamp(int value) {
        if (value < 0) return 0;
        if (value > 100) return 100;
        return value;
    }
}
