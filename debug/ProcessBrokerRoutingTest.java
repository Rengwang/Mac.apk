package com.macapk.loader;

import java.nio.file.*;

/** Manifest process isolation must work with Google services disabled; it must
 * not make a single-process app boot any broker. Runs in a fresh test HOME. */
public final class ProcessBrokerRoutingTest {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        Path xml=root.resolve("processes.xml");
        Files.writeString(xml,"""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.process">
              <application android:process=":ui"><service android:name="test.process.Worker" android:process=":worker"/></application>
            </manifest>
            """);
        Manifest multi=Manifest.parse(xml.toFile());
        check(!SystemClient.useGoogleGmsFile().exists(),"test HOME unexpectedly has Google enabled");
        String name=Boot.chooseProcessName(multi,null);
        boolean off="0".equals(System.getenv("MACAPK_BROKER"))||"0".equals(System.getenv("MACAPK_PROCESS_SPLIT"));
        if(off){check(name.equals("test.process"),"explicit off did not keep old process model");System.out.println("PASS: explicit broker/split opt-out");return;}
        check(name.equals("test.process:ui"),"manifest process collapsed without Google services: "+name);
        check(SystemClient.enabled(),"manifest's worker did not enable process broker");
        check(SystemClient.systemName().equals("org.macapk.processes"),"non-Google app selected Google broker");
        Files.writeString(xml,"""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.single"><application/></manifest>
            """);
        Manifest single=Manifest.parse(xml.toFile());Boot.chooseProcessName(single,null);
        check(!SystemClient.enabled(),"single-process app enabled broker");
        System.out.println("PASS: manifest process isolation without Google; single-process app keeps broker off");
    }
}
