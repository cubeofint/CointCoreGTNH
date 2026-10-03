package coint.ae2;

import appeng.api.networking.IGridConnection;

public interface GridNodeAccess {

    void cointcore$removeConnection(IGridConnection connection);

    void cointcore$validateGrid();
}
