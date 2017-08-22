package com.github.jerrysearch.tns.server.cluster;

public interface CNodeManagerMBean {
	
	/**
	 * 同对方组成集群
	 * @param host
	 * @return
	 */
    String meet(String host);
	/**
	 * 集群状态
	 * @return
	 */
    String clusterStatus();
	/**
	 * 埋葬某个节点
	 * @return
	 */
    String tombstone(long id);
}
