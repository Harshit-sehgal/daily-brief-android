const fs = require('fs');
const path = require('path');
const {
  withAndroidManifest,
  withDangerousMod,
  withGradleProperties,
  withProjectBuildGradle,
} = require('@expo/config-plugins');

const NETWORK_SECURITY_CONFIG = `<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
  <base-config cleartextTrafficPermitted="false" />
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">10.0.2.2</domain>
  </domain-config>
</network-security-config>
`;

function withMobileGradleToolchain(config) {
  config = withProjectBuildGradle(config, (gradleConfig) => {
    if (gradleConfig.modResults.language !== 'groovy') return gradleConfig;

    let contents = gradleConfig.modResults.contents;
    contents = contents.replace(
      /classpath\(['"]org\.jetbrains\.kotlin:kotlin-gradle-plugin['"]\)/,
      "classpath('org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20')"
    );
    if (!contents.includes('mavenLocal()')) {
      contents = contents.replace(
        /maven \{ url ['"]https:\/\/www\.jitpack\.io['"] \}/,
        "$&\n    mavenLocal()"
      );
    }
    if (!contents.includes('ext.compileSdkVersion = 36')) {
      contents = contents.replace(
        'apply plugin: "expo-root-project"',
        '// The mobile engine AARs are compiled with compileSdk 36.\n' +
          'ext.compileSdkVersion = 36\n\n' +
          'apply plugin: "expo-root-project"'
      );
    }
    gradleConfig.modResults.contents = contents;
    return gradleConfig;
  });

  return withGradleProperties(config, (propertiesConfig) => {
    const requiredProperties = [
      ['kotlinVersion', '2.3.20'],
      // Packaging the four-ABI release APK can exceed Expo's generated 2 GB heap.
      ['org.gradle.jvmargs', '-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'],
      // Keep native compilation bounded so the larger packaging heap remains available.
      ['org.gradle.workers.max', '2'],
    ];
    for (const [key, value] of requiredProperties) {
      const existing = propertiesConfig.modResults.find(
        (property) => property.type === 'property' && property.key === key
      );
      if (existing) {
        existing.value = value;
      } else {
        propertiesConfig.modResults.push({ type: 'property', key, value });
      }
    }
    return propertiesConfig;
  });
}

function withLocalEmulatorCleartext(config) {
  config = withAndroidManifest(config, (manifestConfig) => {
    const application = manifestConfig.modResults.manifest.application?.[0];
    if (application) {
      application.$['android:networkSecurityConfig'] = '@xml/network_security_config';
    }
    return manifestConfig;
  });

  config = withMobileGradleToolchain(config);

  return withDangerousMod(config, [
    'android',
    async (dangerousConfig) => {
      const resourceDirectory = path.join(
        dangerousConfig.modRequest.platformProjectRoot,
        'app',
        'src',
        'main',
        'res',
        'xml'
      );
      fs.mkdirSync(resourceDirectory, { recursive: true });
      fs.writeFileSync(
        path.join(resourceDirectory, 'network_security_config.xml'),
        NETWORK_SECURITY_CONFIG
      );
      return dangerousConfig;
    },
  ]);
}

module.exports = withLocalEmulatorCleartext;
