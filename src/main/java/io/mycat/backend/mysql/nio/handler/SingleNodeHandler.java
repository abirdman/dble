/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese
 * opensource volunteers. you can redistribute it and/or modify it under the
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Any questions about this component can be directed to it's project Web address
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.backend.mysql.nio.handler;

import io.mycat.MycatServer;
import io.mycat.backend.BackendConnection;
import io.mycat.backend.datasource.PhysicalDBNode;
import io.mycat.backend.mysql.LoadDataUtil;
import io.mycat.cache.LayerCachePool;
import io.mycat.config.ErrorCode;
import io.mycat.config.MycatConfig;
import io.mycat.log.transaction.TxnLogHelper;
import io.mycat.net.mysql.*;
import io.mycat.route.RouteResultset;
import io.mycat.route.RouteResultsetNode;
import io.mycat.server.NonBlockingSession;
import io.mycat.server.ServerConnection;
import io.mycat.statistic.stat.QueryResult;
import io.mycat.statistic.stat.QueryResultDispatcher;
import io.mycat.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * @author mycat
 */
public class SingleNodeHandler implements ResponseHandler, LoadDataResponseHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SingleNodeHandler.class);

    private final RouteResultsetNode node;
    private final RouteResultset rrs;
    protected final NonBlockingSession session;

    // only one thread access at one time no need lock
    protected volatile byte packetId;
    protected volatile ByteBuffer buffer;
    private long startTime;
    private long netInBytes;
    protected long netOutBytes;
    protected long selectRows;

    private String priamaryKeyTable = null;
    private int primaryKeyIndex = -1;

    private boolean prepared;
    private int fieldCount;
    private List<FieldPacket> fieldPackets = new ArrayList<>();


    private volatile boolean waitingResponse;

    public SingleNodeHandler(RouteResultset rrs, NonBlockingSession session) {
        this.rrs = rrs;
        this.node = rrs.getNodes()[0];
        if (node == null) {
            throw new IllegalArgumentException("routeNode is null!");
        }
        if (session == null) {
            throw new IllegalArgumentException("session is null!");
        }
        this.session = session;
    }


    public void execute() throws Exception {
        startTime = System.currentTimeMillis();
        ServerConnection sc = session.getSource();
        waitingResponse = true;
        this.packetId = 0;
        final BackendConnection conn = session.getTarget(node);
        LOGGER.debug("rrs.getRunOnSlave() " + rrs.getRunOnSlave());
        node.setRunOnSlave(rrs.getRunOnSlave());    // 实现 master/slave注解
        LOGGER.debug("node.getRunOnSlave() " + node.getRunOnSlave());

        if (session.tryExistsCon(conn, node)) {
            execute(conn);
        } else {
            // create new connection
            LOGGER.debug("node.getRunOnSlave() " + node.getRunOnSlave());
            node.setRunOnSlave(rrs.getRunOnSlave());    // 实现 master/slave注解
            LOGGER.debug("node.getRunOnSlave() " + node.getRunOnSlave());

            MycatConfig conf = MycatServer.getInstance().getConfig();
            PhysicalDBNode dn = conf.getDataNodes().get(node.getName());
            dn.getConnection(dn.getDatabase(), sc.isAutocommit(), node, this, node);
        }

    }

    private void execute(BackendConnection conn) {
        if (session.closed()) {
            waitingResponse = false;
            session.clearResources(true);
            return;
        }
        conn.setResponseHandler(this);
        boolean isAutocommit = session.getSource().isAutocommit() && !session.getSource().isTxstart();
        if (!isAutocommit && node.isModifySQL()) {
            TxnLogHelper.putTxnLog(session.getSource(), node.getStatement());
        }
        conn.execute(node, session.getSource(), isAutocommit);
    }

    @Override
    public void connectionAcquired(final BackendConnection conn) {
        session.bindConnection(node, conn);
        execute(conn);

    }

    @Override
    public void connectionError(Throwable e, BackendConnection conn) {
        session.handleSpecial(rrs, session.getSource().getSchema(), true);
        recycleResources();
        session.getSource().close(e.getMessage());
    }

    @Override
    public void errorResponse(byte[] data, BackendConnection conn) {
        ErrorPacket err = new ErrorPacket();
        err.read(data);
        err.packetId = ++packetId;
        backConnectionErr(err, conn);
    }

    private void backConnectionErr(ErrorPacket errPkg, BackendConnection conn) {
        ServerConnection source = session.getSource();
        String errUser = source.getUser();
        String errHost = source.getHost();
        int errPort = source.getLocalPort();

        String errmgs = " errno:" + errPkg.errno + " " + new String(errPkg.message);
        LOGGER.warn("execute  sql err :" + errmgs + " con:" + conn +
                " frontend host:" + errHost + "/" + errPort + "/" + errUser);

        session.releaseConnectionIfSafe(conn, false);

        source.setTxInterrupt(errmgs);
        session.handleSpecial(rrs, session.getSource().getSchema(), false);

        /**
         * TODO: 修复全版本BUG
         *
         * BUG复现：
         * 1、MysqlClient:  SELECT 9223372036854775807 + 1;
         * 2、MyCatServer:  ERROR 1690 (22003): BIGINT value is out of range in '(9223372036854775807 + 1)'
         * 3、MysqlClient: ERROR 2013 (HY000): Lost connection to MySQL server during query
         *
         * Fixed后
         * 1、MysqlClient:  SELECT 9223372036854775807 + 1;
         * 2、MyCatServer:  ERROR 1690 (22003): BIGINT value is out of range in '(9223372036854775807 + 1)'
         * 3、MysqlClient: ERROR 1690 (22003): BIGINT value is out of range in '(9223372036854775807 + 1)'
         *
         */
        // 由于 pakcetId != 1 造成的问题
        if (waitingResponse) {
            errPkg.packetId = 1;
            errPkg.write(source);
            waitingResponse = false;
        }
        recycleResources();
    }


    /**
     * insert/update/delete
     * <p>
     * okResponse()：读取data字节数组，组成一个OKPacket，并调用ok.write(source)将结果写入前端连接FrontendConnection的写缓冲队列writeQueue中，
     * 真正发送给应用是由对应的NIOSocketWR从写队列中读取ByteBuffer并返回的
     */
    @Override
    public void okResponse(byte[] data, BackendConnection conn) {
        //
        this.netOutBytes += data.length;

        boolean executeResponse = conn.syncAndExcute();
        if (executeResponse) {
            session.handleSpecial(rrs, session.getSource().getSchema(), true);
            ServerConnection source = session.getSource();
            OkPacket ok = new OkPacket();
            ok.read(data);
            if (rrs.isLoadData()) {
                byte lastPackId = source.getLoadDataInfileHandler().getLastPackId();
                ok.packetId = ++lastPackId; // OK_PACKET
                source.getLoadDataInfileHandler().clear();

            } else {
                ok.packetId = ++packetId; // OK_PACKET
            }
            ok.serverStatus = source.isAutocommit() ? 2 : 1;
            source.setLastInsertId(ok.insertId);
            //handleSpecial
            session.releaseConnectionIfSafe(conn, false);
            ok.write(source);
            waitingResponse = false;
        }
    }


    /**
     * select
     * <p>
     * 行结束标志返回时触发，将EOF标志写入缓冲区，最后调用source.write(buffer)将缓冲区放入前端连接的写缓冲队列中，等待NIOSocketWR将其发送给应用
     */
    @Override
    public void rowEofResponse(byte[] eof, boolean isLeft, BackendConnection conn) {

        this.netOutBytes += eof.length;

        ServerConnection source = session.getSource();
        // 判断是调用存储过程的话不能在这里释放链接
        if (!rrs.isCallStatement() || rrs.getProcedure().isResultSimpleValue()) {
            session.releaseConnectionIfSafe(conn, false);
        }

        eof[3] = ++packetId;
        buffer = source.writeToBuffer(eof, allocBuffer());
        int resultSize = source.getWriteQueue().size() * MycatServer.getInstance().getConfig().getSystem().getBufferPoolPageSize();
        resultSize = resultSize + buffer.position();
        source.write(buffer);
        waitingResponse = false;

        if (MycatServer.getInstance().getConfig().getSystem().getUseSqlStat() == 1) {
            if (rrs.getStatement() != null) {
                netInBytes += rrs.getStatement().getBytes().length;
            }
            //查询结果派发
            QueryResult queryResult = new QueryResult(session.getSource().getUser(), rrs.getSqlType(), rrs.getStatement(), selectRows,
                    netInBytes, netOutBytes, startTime, System.currentTimeMillis(), resultSize);
            QueryResultDispatcher.dispatchQuery(queryResult);
        }
    }

    private void recycleResources() {

        ByteBuffer buf = buffer;
        if (buf != null) {
            session.getSource().recycle(buffer);
            buffer = null;
        }
    }

    /**
     * lazy create ByteBuffer only when needed
     *
     * @return
     */
    protected ByteBuffer allocBuffer() {
        if (buffer == null) {
            buffer = session.getSource().allocate();
        }
        return buffer;
    }

    /**
     * select
     * <p>
     * 元数据返回时触发，将header和元数据内容依次写入缓冲区中
     */
    @Override
    public void fieldEofResponse(byte[] header, List<byte[]> fields, List<FieldPacket> fieldPacketsnull, byte[] eof,
                                 boolean isLeft, BackendConnection conn) {
        this.netOutBytes += header.length;
        for (byte[] field : fields) {
            this.netOutBytes += field.length;
        }

        String primaryKey = null;
        if (rrs.hasPrimaryKeyToCache()) {
            String[] items = rrs.getPrimaryKeyItems();
            priamaryKeyTable = items[0];
            primaryKey = items[1];
        }

        header[3] = ++packetId;

        ServerConnection source = session.getSource();
        buffer = source.writeToBuffer(header, allocBuffer());
        for (int i = 0, len = fields.size(); i < len; ++i) {
            byte[] field = fields.get(i);
            field[3] = ++packetId;

            // 保存field信息
            FieldPacket fieldPk = new FieldPacket();
            fieldPk.read(field);
            fieldPackets.add(fieldPk);

            // find primary key index
            if (primaryKey != null && primaryKeyIndex == -1) {
                String fieldName = new String(fieldPk.name);
                if (primaryKey.equalsIgnoreCase(fieldName)) {
                    primaryKeyIndex = i;
                }
            }

            buffer = source.writeToBuffer(field, buffer);
        }

        fieldCount = fieldPackets.size();

        eof[3] = ++packetId;
        buffer = source.writeToBuffer(eof, buffer);
    }

    /**
     * select
     * <p>
     * 行数据返回时触发，将行数据写入缓冲区中
     */
    @Override
    public boolean rowResponse(byte[] row, RowDataPacket rowPacket, boolean isLeft, BackendConnection conn) {

        this.netOutBytes += row.length;
        this.selectRows++;
        row[3] = ++packetId;

        RowDataPacket rowDataPk = null;
        // cache primaryKey-> dataNode
        if (primaryKeyIndex != -1) {
            rowDataPk = new RowDataPacket(fieldCount);
            rowDataPk.read(row);
            String primaryKey = new String(rowDataPk.fieldValues.get(primaryKeyIndex));
            RouteResultsetNode rNode = (RouteResultsetNode) conn.getAttachment();
            LayerCachePool pool = MycatServer.getInstance().getRouterService().getTableId2DataNodeCache();
            if (pool != null) {
                pool.putIfAbsent(priamaryKeyTable, primaryKey, rNode.getName());
            }
        }

        if (prepared) {
            if (rowDataPk == null) {
                rowDataPk = new RowDataPacket(fieldCount);
                rowDataPk.read(row);
            }
            BinaryRowDataPacket binRowDataPk = new BinaryRowDataPacket();
            binRowDataPk.read(fieldPackets, rowDataPk);
            binRowDataPk.packetId = rowDataPk.packetId;
//            binRowDataPk.write(session.getSource());
            /*
             * [fix bug] : 这里不能直接将包写到前端连接,
             * 因为在fieldEofResponse()方法结束后buffer还没写出,
             * 所以这里应该将包数据顺序写入buffer(如果buffer满了就写出),然后再将buffer写出
             */
            buffer = binRowDataPk.write(buffer, session.getSource(), true);
        } else {
            buffer = session.getSource().writeToBuffer(row, allocBuffer());
            //session.getSource().write(row);
        }
        return false;
    }

    @Override
    public void writeQueueAvailable() {

    }

    @Override
    public void connectionClose(BackendConnection conn, String reason) {
        ErrorPacket err = new ErrorPacket();
        err.packetId = ++packetId;
        err.errno = ErrorCode.ER_ERROR_ON_CLOSE;
        err.message = StringUtil.encode(reason, session.getSource().getCharset());
        this.backConnectionErr(err, conn);
        session.getSource().close(reason);
    }

    public void clearResources() {

    }

    @Override
    public void requestDataResponse(byte[] data, BackendConnection conn) {
        LoadDataUtil.requestFileDataResponse(data, conn);
    }

    public void setPrepared(boolean prepared) {
        this.prepared = prepared;
    }

    @Override
    public String toString() {
        return "SingleNodeHandler [node=" + node + ", packetId=" + packetId + "]";
    }


    @Override
    public void relayPacketResponse(byte[] relayPacket, BackendConnection conn) {

    }


    @Override
    public void endPacketResponse(byte[] endPacket, BackendConnection conn) {

    }

}
