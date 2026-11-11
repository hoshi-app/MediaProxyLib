Pod::Spec.new do |s|
  s.name           = 'MediaProxy'
  s.version        = '0.1.0'
  s.summary        = 'Локальный HLS-прокси с дисковым кэшем для react-native-video/expo-video'
  s.description    = 'Loopback HTTP-прокси: заголовки сессии, перезапись HLS-плейлистов, кэш сегментов, Range, prefetch, ретраи.'
  s.author         = 'MediaProxyLib'
  s.homepage       = 'https://github.com/MediaProxyLib'
  s.platforms      = { :ios => '15.0' }
  s.source         = { :path => '.' }
  s.static_framework = true
  s.swift_version  = '5.9'

  s.dependency 'ExpoModulesCore'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule'
  }

  s.source_files = '**/*.swift'
end
