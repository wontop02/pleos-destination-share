# Default room creation failure — November 9

The user clarified that room creation failed when they used the app, while the coordinator had created rooms successfully. The coordinator had changed the form's server address manually to4192, so its earlier success did not establish the default user path.

MainActivity.kt initializes the real server field to http://127.0.0.1:4182. VehicleViews.field uses setText(initial), not a placeholder, and submit forwards that text to RoomHttpClient.create. The isolated owned test server was running on host4192. Both owned AVDs had only the4192-to4192 reverse mapping; there was no4182 route. The actual app-default room POST from each AVD failed with Connection refused. before-fix.json records the red run of probe-default-create.py.

The environment fix adds device4182 to owned host4192 on each approved AVD with --no-rebind, preserving the existing4192 mapping. No APK, app source, current app identity, room or voice draft was changed. The same probe then received HTTP201 for an isolated room from each AVD using the default endpoint and default public vehicle name. Each newly created probe room was deleted through its own host credential, returning200. Response tokens/invitations stayed in memory and were never printed or saved. Existing connected user/demo rooms were not deleted or replaced. after-fix.json records the green run.

This is actual default-endpoint/API verification. It is not a claim of a new complete manual UI scenario or issue25 acceptance PASS. The failure was an omitted environment setup step, not a user input mistake. The earlier frame-layout and connection repairs did not fix this endpoint mismatch.

Every future dedicated-car startup must run apply-default-route.py after both approved transports boot: adb reverse mappings are transport-scoped and disappear on restart. The helper refuses to overwrite a different existing4182 mapping. It uses only dedicated ADB5041, emulator-5570/Pleos12Leader and emulator-5572/Pleos12Follower, user10. The prior4190 server and original issue25 implementation deadline remain unchanged.
