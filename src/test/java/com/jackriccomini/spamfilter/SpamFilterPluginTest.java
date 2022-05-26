package com.jackriccomini.spamfilter;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SpamFilterPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SpamFilterPlugin.class);
		RuneLite.main(args);
	}
}