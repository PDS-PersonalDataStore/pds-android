# Architecture

This document describes the design of PDS Android.

## System Components

PDS Android has three components:

1. `top.tau2c.pds.core`: Android app module that hosts `PdsService`.
2. `top.tau2c.pds.client`: Android library module that supplies `PdsAndroidClient`.
3. `pds-ffi`: Rust library that contains local storage and sync routines.

## Component Interactions

The client app talks to `PdsService` through Android Binder IPC.
Then `PdsService` calls functions in `pds-ffi` through JNI.

## IPC Protocol

The AIDL interface `IPdsService` defines three methods:

1. `registerClient(clientPackageName: String, clientPublicKeyHex: String): String`
2. `handleIpcRequest(requestJson: String): String`
3. `isApproved(clientPackageName: String): Boolean`

All requests and responses use JSON format.

## Security Model

The service checks the caller identity with `Binder.getCallingUid()`.
The client app must request the permission `top.tau2c.pds.permission.ACCESS_PDS`.
