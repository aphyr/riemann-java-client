package com.aphyr.riemann.client;

public class ServerError extends Exception {
  public final String message;

  public ServerError(String message) {
    this.message = message;
  }

  public String getMessage() {
    return message;
  }
}
