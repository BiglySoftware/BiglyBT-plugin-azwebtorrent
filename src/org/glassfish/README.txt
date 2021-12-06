This is grizzly-framework-3.0.1.jar with a bug fix for 100% CPU in SSLBaseFilter caused by things getting stuck in a loop

	case NEED_WRAP: {
                    if (isLoggingFinest) {
                        LOGGER.log(Level.FINEST, "NEED_WRAP Engine: {0}", sslCtx.getSslEngine());
                    }

                    tmpNetBuffer = handshakeWrap(connection, sslCtx, tmpNetBuffer);
                    handshakeStatus = sslCtx.getSslEngine().getHandshakeStatus();

                    break;
                    
status never changes from NEED_WRAP

TransportContext::getHandshakeStatus

	  } else if (!isOutboundClosed() && isInboundClosed()) {
            // Special case that the inbound was closed, but outbound open.
            return HandshakeStatus.NEED_WRAP;
        }
        
is causing this