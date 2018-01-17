package com.booking.replication.mysql.binlog.model;

import java.io.Serializable;
import java.util.BitSet;
import java.util.List;

@SuppressWarnings("unused")
public interface DeleteRowsEventData extends EventData {
    long getTableId();
    BitSet getIncludedColumns();
    List<Serializable[]> getRows();
}
