package com.intel.cosbench.app.driver;

import com.intel.cosbench.api.auth.AuthAPI;
import com.intel.cosbench.api.auth.AuthAPIFactory;

public final class FailingAuthFactory implements AuthAPIFactory {
    @Override
    public String getAuthName() {
        return "failing";
    }

    @Override
    public AuthAPI getAuthAPI() {
        return new FailingAuth();
    }
}
