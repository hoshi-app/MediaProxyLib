Pod::Spec.new do |s|
  s.name           = 'MediaProxy'
  s.version        = '0.1.0'
  s.summary        = 'Loopback HLS proxy with disk cache for react-native-video and expo-video'
  s.description    = 'Loopback HTTP proxy: session headers, HLS playlist rewriting, segment cache, Range, prefetch, retries.'
  s.author         = 'kalempurt'
  s.homepage       = 'https://github.com/hoshi-app/MediaProxyLib'
  s.license        = { :type => 'MIT', :file => '../LICENSE' }
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
