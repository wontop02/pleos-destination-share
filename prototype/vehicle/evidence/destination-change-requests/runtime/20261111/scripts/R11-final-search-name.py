import sys,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent));import runtime_tools as t
a='emulator-5570'
p=t.instrument(a,'VehicleUiActionProbe',resource='change_destination',operation='click');assert p.get('actionAccepted')=='true'
p=t.instrument(a,'VehicleUiTextProbe',field='search_keyword',textKey='search-name');assert p.get('success')=='true'
t.capture(a,'R11-final-name-input');p=t.instrument(a,'VehicleUiActionProbe',resource='search_native_places',operation='click');assert p.get('actionAccepted')=='true'
time.sleep(2);print(t.capture(a,'R11-final-name-native-results'))
