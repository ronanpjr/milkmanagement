const path = require('path');
const { FusesPlugin } = require('@electron-forge/plugin-fuses');
const { flipFuses, FuseV1Options, FuseVersion } = require('@electron/fuses');

// Single source of truth for the fuse wire. `npm run make` (electron-forge)
// consumes it through FusesPlugin below; everything that is actually shipped
// goes through electron-builder (`npm run electron:build` / `:publish`), which
// never runs forge plugins — without the afterPack hook at the bottom of this
// file the distributed binary would keep RunAsNode=true and
// EnableCookieEncryption=false.
//
// EnableEmbeddedAsarIntegrityValidation is safe here because electron-builder
// embeds the asar header hash itself (Windows: `addWinAsarIntegrity` in
// app-builder-lib, before afterPack runs; macOS: same data in Info.plist).
// Electron only enforces the check on macOS/Windows, and this project ships
// nsis (win) + deb/rpm (linux) only.
const FUSE_CONFIG = {
    version: FuseVersion.V1,
    [FuseV1Options.RunAsNode]: false,
    [FuseV1Options.EnableCookieEncryption]: true,
    [FuseV1Options.EnableNodeOptionsEnvironmentVariable]: false,
    [FuseV1Options.EnableNodeCliInspectArguments]: false,
    [FuseV1Options.EnableEmbeddedAsarIntegrityValidation]: true,
    [FuseV1Options.OnlyLoadAppFromAsar]: true,
};

module.exports = {
  packagerConfig: {
    asar: true,
  },
  rebuildConfig: {},
  makers: [
    {
      name: '@electron-forge/maker-squirrel',
      config: {},
    },
    {
      name: '@electron-forge/maker-zip',
      platforms: ['darwin'],
    },
    {
      name: '@electron-forge/maker-deb',
      config: {},
    },
    {
      name: '@electron-forge/maker-rpm',
      config: {},
    },
  ],
  plugins: [
    {
      name: '@electron-forge/plugin-auto-unpack-natives',
      config: {},
    },
    // Fuses are used to enable/disable various Electron functionality
    // at package time, before code signing the application
    new FusesPlugin(FUSE_CONFIG),
  ],

  // electron-builder hook, wired through package.json `build.afterPack`. The
  // hook has to be an exported named function of the module, so it lives here
  // (next to the fuse definition it uses) rather than in scripts/, which
  // belongs to another PR. electron-builder requires the module and picks the
  // `afterPack` export.
  afterPack: async (context) => {
    const { appOutDir, packager } = context;
    // `electronPlatformName` is the target being packaged — `packager.platform`
    // reports the host, so a `--win` build on Linux would otherwise take the
    // Linux branch.
    const platform = context.electronPlatformName || packager.platform.name;
    const productFilename = packager.appInfo.productFilename;

    let electronBinary;
    if (platform === 'darwin') {
      electronBinary = path.join(appOutDir, `${productFilename}.app`);
    } else if (platform === 'win32') {
      electronBinary = path.join(appOutDir, `${productFilename}.exe`);
    } else {
      electronBinary = path.join(appOutDir, packager.executableName);
    }

    const sentinels = await flipFuses(electronBinary, FUSE_CONFIG);
    console.log(`[fuses] ${electronBinary}: ${sentinels} fuse wire(s) applied`);
  },
};
