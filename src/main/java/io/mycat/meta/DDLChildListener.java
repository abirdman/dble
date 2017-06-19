package io.mycat.meta;

import io.mycat.MycatServer;
import io.mycat.config.loader.zkprocess.comm.ZkConfig;
import io.mycat.config.loader.zkprocess.comm.ZkParamCfg;
import io.mycat.config.loader.zkprocess.zookeeper.process.DDLInfo;
import io.mycat.config.loader.zkprocess.zookeeper.process.DDLInfo.DDLStatus;
import io.mycat.util.StringUtil;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Created by huqing.yan on 2017/6/6.
 */
public class DDLChildListener implements PathChildrenCacheListener {
	private static final Logger LOGGER = LoggerFactory.getLogger(PathChildrenCacheListener.class);
	@Override
	public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
		ChildData childData = event.getData();
		switch (event.getType()) {
			case CHILD_ADDED:
				try {
					lockTableByNewNode(childData);
				}catch(Exception e){
					LOGGER.warn("CHILD_ADDED error",e );
				}
				break;
			case CHILD_UPDATED:
				updateMeta(childData);
				break;
			case CHILD_REMOVED:
				deleteNode(childData);
				break;
			default:
				break;
		}
	}

	private void lockTableByNewNode(ChildData childData) throws Exception {
		String data = new String(childData.getData(), StandardCharsets.UTF_8);
		LOGGER.info("DDL node "+childData.getPath() +" created , and data is "+data);
		DDLInfo ddlInfo = new DDLInfo(data);
		if (ddlInfo.getFrom().equals(ZkConfig.getInstance().getValue(ZkParamCfg.ZK_CFG_MYID))) {
			return; //self node
		}
		if (DDLStatus.INIT != ddlInfo.getStatus()) {
			return;
		}
		String nodeName = childData.getPath().substring(childData.getPath().lastIndexOf("/") + 1);
		String[] tableInfo = nodeName.split("\\.");
		String schema = StringUtil.removeBackQuote(tableInfo[0]);
		String table = StringUtil.removeBackQuote(tableInfo[1]);
		try {
			MycatServer.getInstance().getTmManager().addMetaLock(schema, table);
		} catch (Exception t) {
			MycatServer.getInstance().getTmManager().removeMetaLock(schema, table);
			throw t;
		}
	}

	private void updateMeta(ChildData childData) {
		String data = new String(childData.getData(), StandardCharsets.UTF_8);
		LOGGER.info("DDL node "+childData.getPath() +" updated , and data is "+data);
		DDLInfo ddlInfo = new DDLInfo(data);
		if (ddlInfo.getFrom().equals(ZkConfig.getInstance().getValue(ZkParamCfg.ZK_CFG_MYID))) {
			return; //self node
		}
		if (DDLStatus.INIT == ddlInfo.getStatus()) {
			return;
		}
		MycatServer.getInstance().getTmManager().updateMetaData(ddlInfo.getSchema(), ddlInfo.getSql(), DDLStatus.SUCCESS.equals(ddlInfo.getStatus()));
	}

	private void deleteNode(ChildData childData){
		String data = new String(childData.getData(), StandardCharsets.UTF_8);
		DDLInfo ddlInfo = new DDLInfo(data);
		LOGGER.info("DDL node "+childData.getPath() +" removed , and DDL info is "+ddlInfo.toString());
	}
}