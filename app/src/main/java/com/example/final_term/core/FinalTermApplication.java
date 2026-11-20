package com.example.final_term.core;

import android.app.Application;

public final class FinalTermApplication extends Application {
    private AppContainer appContainer;

    @Override
    public void onCreate() {
        super.onCreate();
        appContainer = new AppContainer(this);
    }

    public AppContainer getAppContainer() {
        return appContainer;
    }

    public void refreshContainer() {
        appContainer = new AppContainer(this);
    }
}
