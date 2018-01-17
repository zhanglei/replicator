package com.booking.replication.mysql.binlog.model;

import java.io.Serializable;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

public interface UpdateRowsEventData extends EventData {
    long getTableId();
    BitSet getIncludedColumnsBeforeUpdate();
    BitSet getIncludedColumns();
    List<Map.Entry<Serializable[], Serializable[]>> getRows();
}
