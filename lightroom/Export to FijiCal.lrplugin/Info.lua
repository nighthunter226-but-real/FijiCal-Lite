-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 nighthunter226-but-real and FijiCal Lite contributors.
-- Distributed without warranty; see LICENSE and COPYRIGHT.md.
return {
    LrSdkVersion = 9.0,
    LrSdkMinimumVersion = 6.0,
    LrToolkitIdentifier = 'org.fijical.lightroom.export',
    LrPluginName = 'Export to FijiCal',
    LrPluginInfoUrl = 'https://developer.adobe.com/lightroom-classic',
    LrExportServiceProvider = {
        title = 'FijiCal',
        file = 'ExportServiceProvider.lua',
    },
    VERSION = { major = 0, minor = 9, revision = 0, build = 4 },
}
