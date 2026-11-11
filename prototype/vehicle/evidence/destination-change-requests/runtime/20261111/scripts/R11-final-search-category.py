import sys,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent));import runtime_tools as t
a='emulator-5570';t.launch(a)
if not any(n.get('resource-id')==t.PACKAGE+':id/search_keyword' for n in t.nodes(a)):
 p=t.instrument(a,'VehicleUiActionProbe',resource='change_destination',operation='click');assert p.get('actionAccepted')=='true'
p=t.instrument(a,'VehicleUiTextProbe',field='search_keyword',textKey='cafe');assert p.get('success')=='true'
t.capture(a,'R11-final-category-input');p=t.instrument(a,'VehicleUiActionProbe',resource='search_native_places',operation='click');assert p.get('actionAccepted')=='true'
time.sleep(2);t.capture(a,'R11-final-category-native-results')
t.tap_nearest_text(a,'프레소커피랩');t.capture(a,'R11-final-native-place-selected');t.tap_nearest_text(a,'목적지로');time.sleep(12);print(t.capture(a,'R11-final-native-guidance-started'))
