package com.github.jerrysearch.tns.server.cluster;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;

import com.github.jerrysearch.tns.protocol.rpc.Cluster;
import com.github.jerrysearch.tns.protocol.rpc.State;
import com.github.jerrysearch.tns.protocol.rpc.TCNode;
import com.github.jerrysearch.tns.protocol.rpc.structConstants;
import com.github.jerrysearch.tns.server.conf.Config;
import com.github.jerrysearch.tns.server.util.DateUtil;
import com.jcabi.aspects.Loggable;

public class CNodeManager implements CNodeManagerMBean {

	private final TreeMap<Long, TCNode> cMap = new TreeMap<Long, TCNode>();
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final Lock readLock = lock.readLock();
	private final Lock writeLock = lock.writeLock();

	private final long myId = Config.TNSID;

	private CNodeManager() {

		TCNode me = new TCNode();
		me.setHost(Config.HOSTNAME);
		me.setPort(structConstants.PORT);
		me.setId(myId);
		me.setState(State.Joining);
		me.setTimestamp(System.currentTimeMillis());
		/**
		 * 将自己放到列表中
		 */
		this.cMap.put(myId, me);
	}

	private TCNode getMe() {
		return this.cMap.get(this.myId);
	}

	/**
	 * 选择一个其它节点进行同步 如果没有其它节点，返回null 1 -> 2 -> 3 -> 1
	 * 
	 * @return
	 */
	public TCNode getNext() {
		try {
			this.readLock.lock();
			return this.getNext(this.myId);
		} finally {
			this.readLock.unlock();
		}
	}

	/**
	 * 可用的cluster列表
	 * 
	 * @param list
	 */
	public void toUpClusterNodeList(List<TCNode> list) {
		try {
			this.readLock.lock();
			Collection<TCNode> collection = this.cMap.values();
			for (TCNode tcnode : collection) {
				if (tcnode.getState() == State.UP) {
					list.add(tcnode);
				}
			}
		} finally {
			this.readLock.unlock();
		}
	}

	/**
	 * 完整的cluster列表
	 * 
	 * @param list
	 */
	public void toAllClusterNodeList(List<TCNode> list) {
		try {
			this.readLock.lock();
			list.addAll(this.cMap.values());
		} finally {
			this.readLock.unlock();
		}
	}

	private TCNode getNext(long id) {
		Long key = this.cMap.higherKey(id);
		if (null == key) {
			key = this.cMap.firstKey();
		}
		/**
		 * 选择到自己，终止
		 */
		if (key == this.myId) {
			return null;
		}
		TCNode tcnode = this.cMap.get(key);
		/**
		 * 跳过不健康的节点
		 */
		switch (tcnode.getState()) {
		case Joining:
		case UP:
		case DOWN_1:
		case DOWN_2:
			return tcnode;
		case Leaving:
		case Tombstone:
		case DOWN:
			return this.getNext(key);
		default:
			return null;
		}
	}

	@Override
	@Loggable
	public String meet(String host) {
		TSocket transport = new TSocket(host, structConstants.PORT, 2000);
		TProtocol protocol = new TBinaryProtocol(transport);
		Cluster.Client client = new Cluster.Client(protocol);
		try {
			transport.open();
			client.up(this.getMe());
			return "OK !";
		} catch (Exception e) {
			return String.format("%s, Exception : %s", "FAIL", e.getMessage());
		} finally {
			if (transport.isOpen()) {
				transport.close();
			}
		}
	}

	/**
	 * 某个节点上线了
	 * 
	 * @param tcnode
	 */
	public void up(TCNode tcnode) {
		Long key = tcnode.getId();
		try {
			this.writeLock.lock();
			this.cMap.put(key, tcnode);
		} finally {
			this.writeLock.unlock();
		}

	}

	private final String format = "    %-20s%-20s%-20s%-20s%-20s%-20s\n";
	private final String headLine = String.format(format, "STATE", "HOST", "ID", "VERSION",
			"TIMESTAMP", "TIME");

	@Override
	public String clusterStatus() {
		try {
			StringBuilder sb = new StringBuilder(500);
			sb.append(headLine).append("\n");
			this.readLock.lock();
			Collection<TCNode> collection = this.cMap.values();
			for (TCNode tcnode : collection) {
				String s = String.format(format, tcnode.getState().toString(), tcnode.getHost(),
						tcnode.getId(), tcnode.getVersion(), tcnode.getTimestamp(),
						DateUtil.dateTimeFormat.format(new Date(tcnode.getTimestamp())));
				sb.append(s);
			}
			return sb.toString();
		} finally {
			this.readLock.unlock();
		}
	}

	public void pushClusterList(List<TCNode> list) {
		try {
			this.writeLock.lock();
			for (TCNode tcnode : list) {
				long id = tcnode.getId();
				if (this.cMap.containsKey(id)) {
					TCNode tmp = this.cMap.get(id);
					if (tmp.getState() == State.Tombstone || tmp.getState() == State.Tombstone_1) { // 墓碑是不可恢复的，一个完整周期后，墓碑会传播到所有节点
						continue;
					}
					switch (tcnode.getState()) {
					case Joining:
					case UP:
					case DOWN_1:
					case DOWN_2:
					case DOWN:
						if (tcnode.getTimestamp() > tmp.getTimestamp()) {
							this.cMap.put(id, tcnode); // 更新
						}
						break;
					case Leaving:
					case Tombstone_1:
					case Tombstone:
						this.cMap.put(id, tcnode); // 更新
						break;
					}
				} else {
					this.cMap.put(id, tcnode);
				}
			}
		} finally {
			this.writeLock.unlock();
		}
	}

	@Override
	@Loggable
	public String tombstone(long id) {
		return "UNALLOWED";
		// try {
		// this.writeLock.lock();
		// if (this.cMap.containsKey(id)) {
		// TCNode tcnode = this.cMap.get(id);
		// if (tcnode.getState() != State.DOWN) {
		// return "SORRY ! you can just Tombstone node which status is down !";
		// }
		// tcnode.setState(State.Tombstone);
		// tcnode.setTimestamp(System.currentTimeMillis());
		// return "OK !";
		// } else {
		// return "FAIL !";
		// }
		// } finally {o
		// this.writeLock.unlock();
		// }
	}

	private static class proxy {
		private static final CNodeManager instance = new CNodeManager();
	}

	public static CNodeManager getInstance() {
		return proxy.instance;
	}
}
