package com.darchan.elements.iface;

import java.util.List;

public interface IBus {

	List<ISignal> getSignals();

	IBus slice(IRange range);

	ISignal getSignal(int idx);
	
}
