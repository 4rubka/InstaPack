package com.instapack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ZipIndex {
	private static final Map<ZipFile, ZipIndex> INDEXES = Collections.synchronizedMap(new WeakHashMap<>());

	private final String[] names;
	private final ZipEntry[] entries;

	private ZipIndex(ZipFile zip) {
		List<ZipEntry> files = new ArrayList<>();
		for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
			ZipEntry entry = e.nextElement();
			if (!entry.isDirectory()) files.add(entry);
		}
		files.sort((a, b) -> a.getName().compareTo(b.getName()));
		this.entries = files.toArray(new ZipEntry[0]);
		this.names = new String[entries.length];
		for (int i = 0; i < entries.length; i++) names[i] = entries[i].getName();
	}

	public static ZipIndex of(ZipFile zip) {
		ZipIndex index = INDEXES.get(zip);
		if (index == null) {
			synchronized (INDEXES) {
				index = INDEXES.computeIfAbsent(zip, ZipIndex::new);
			}
		}
		return index;
	}

	public interface Visitor {
		void visit(String name, ZipEntry entry);
	}

	public void forEachWithPrefix(String prefix, Visitor visitor) {
		int i = Arrays.binarySearch(names, prefix);
		if (i < 0) i = -i - 1;
		for (; i < names.length && names[i].startsWith(prefix); i++) visitor.visit(names[i], entries[i]);
	}
}
