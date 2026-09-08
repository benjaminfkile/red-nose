// Jest mock for react-native-vision-camera.  The v5 module ships as ESM which
// Jest cannot parse without Babel transformation; the ScanScreen only needs
// stubs to render in unit tests.

const React = require('react');

const Camera = () => null;

const useCameraDevice = () => ({ position: 'back' });

const useCameraPermission = () => ({
  status: 'granted',
  hasPermission: true,
  canRequestPermission: false,
  requestPermission: async () => true,
});

const useObjectOutput = () => ({});

const isScannedCode = () => false;

module.exports = {
  Camera,
  useCameraDevice,
  useCameraPermission,
  useObjectOutput,
  isScannedCode,
};
