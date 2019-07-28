package com.darchan.elements.iface;

public interface ILogicUnit {

    boolean evaluate(IBus inputBus);

    IRange getAllowedInputBusWidth();

}
