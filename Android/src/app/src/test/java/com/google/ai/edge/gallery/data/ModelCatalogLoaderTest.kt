package com.google.ai.edge.gallery.data

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogLoaderTest {
  private fun catalog(name: String) = """{"models":[{"name":"$name","modelId":"test/$name","modelFile":"model.litertlm","commitHash":"main","description":"test","sizeInBytes":1}]}"""

  @Test fun validRemoteIsUsedAndCached() {
    var saved: String? = null
    val json = catalog("remote")
    val result = ModelCatalogLoader.load({ json }, { error("Cache should not be read") }, { error("Bundle should not be read") }, { saved = it })!!
    assertEquals(ModelCatalogLoader.Source.REMOTE,result.source)
    assertEquals("remote",result.catalog.models.single().name)
    assertEquals(json,saved)
  }
  @Test fun networkFailureUsesValidCache() {
    val result = ModelCatalogLoader.load({ throw java.io.IOException("offline") }, { catalog("cached") }, { error("Unexpected bundle read") })!!
    assertEquals(ModelCatalogLoader.Source.CACHE,result.source)
    assertEquals("cached",result.catalog.models.single().name)
  }
  @Test fun firstInstallOfflineUsesBundledCatalog() {
    val result = ModelCatalogLoader.load({ null }, { null }, { catalog("bundled") })!!
    assertEquals(ModelCatalogLoader.Source.BUNDLED,result.source)
    assertEquals("bundled",result.catalog.models.single().name)
  }
  @Test fun invalidRemoteAndCacheCannotHideBundledCatalog() {
    for (invalid in listOf("{broken", "{}", "null", "{\"models\":null}", "{\"models\":[]}", "{\"models\":[null]}", "{\"models\":[{}]}")) {
      val result = ModelCatalogLoader.load({ invalid }, { invalid }, { catalog("fallback") }, { fail("Must not cache invalid response") })!!
      assertEquals(invalid,ModelCatalogLoader.Source.BUNDLED,result.source)
    }
  }
  @Test fun failedCacheWriteDoesNotDiscardValidNetworkCatalog() {
    val result = ModelCatalogLoader.load({ catalog("remote") }, { null }, { null }, { throw java.io.IOException("disk full") })!!
    assertEquals(ModelCatalogLoader.Source.REMOTE,result.source)
  }
  @Test fun missingAllSourcesReturnsFailure() {
    assertNull(ModelCatalogLoader.load({ null }, { null }, { null }))
  }
  @Test(expected = CancellationException::class) fun cancellationIsNotSwallowed() {
    ModelCatalogLoader.load({ throw CancellationException() }, { catalog("cache") }, { catalog("bundle") })
  }
  @Test fun catalogCompatibilityDoesNotFollowDisplayVersionSuffix() {
    assertTrue(com.google.ai.edge.gallery.BuildConfig.VERSION_NAME.contains("-lan"))
    assertEquals("1_0_20",ModelCatalogLoader.VERSION)
    assertTrue(ModelCatalogLoader.URL.endsWith("/1_0_20.json"))
    assertFalse(ModelCatalogLoader.URL.contains("-lan"))
  }
}
