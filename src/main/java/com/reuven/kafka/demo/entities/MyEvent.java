package com.reuven.kafka.demo.entities;


public record MyEvent(String msg, int id, boolean isThrowException) {

}
