package net.dataforte.infinipedia;

import org.infinispan.loaders.keymappers.TwoWayKey2StringMapper;

public class KeyMapper implements TwoWayKey2StringMapper {

	@Override
	public boolean isSupportedType(Class<?> keyType) {
		return true;
	}

	@Override
	public String getStringMapping(Object key) {
		return key.toString();
	}

	@Override
	public Object getKeyMapping(String stringKey) {
		return stringKey;
	}

}
