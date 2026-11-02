package com.intel.cosbench.app.driver;

import com.intel.cosbench.api.auth.NoneAuth;

final class FailingAuth extends NoneAuth {
    @Override
    public com.intel.cosbench.api.context.AuthContext login() {
        throw new IllegalStateException("provider authentication rejected");
    }
}
