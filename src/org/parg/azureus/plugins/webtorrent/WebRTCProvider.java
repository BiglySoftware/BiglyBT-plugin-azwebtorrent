/*
 * Created on Jan 6, 2016
 * Created by Paul Gardner
 * 
 * Copyright 2016 Azureus Software, Inc.  All rights reserved.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or 
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */


package org.parg.azureus.plugins.webtorrent;

public interface 
WebRTCProvider 
{
	public int
	getPort();
		
	public void
	getOffer(
		byte[]			info_hash,
		long			timeout,
		OfferListener	offer_listener );
	
	public void
	gotAnswer(
		String		offer_id,
		String		sdp );
	
	public void
	gotOffer(
		byte[]			info_hash,
		String			offer_id,
		String			sdp,
		AnswerListener	listener );
	
	public void
	destroy();
	
	public interface
	Offer
	{
		public String
		getOfferID();
		
		public String
		getSDP();
	}
	
	public interface
	Answer
	{
		public String
		getOfferID();
		
		public String
		getSDP();
	}
	
	public interface
	AnswerListener
	{
		public void
		gotAnswer(
			Answer		answer );
		
		public void
		failed();
	}
	
	public interface
	OfferListener
	{
		public void
		gotOffer(
			Offer		offer );
		
		public void
		failed();
	}
	
    public interface
    Callback
    {
    	public void
    	requestNewBrowser();
    }
}
