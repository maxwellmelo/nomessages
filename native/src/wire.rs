use anyhow::{Result, ensure};

pub const MAX_STATE: usize = 16 * 1024 * 1024;
// Complete application fragment: four-byte FrameCodec header + 16 KiB padded body.
pub const MAX_FRAME: usize = 4 + 16 * 1024;

pub struct Reader<'a> {
    data: &'a [u8],
    at: usize,
}
impl<'a> Reader<'a> {
    pub fn new(data: &'a [u8]) -> Result<Self> {
        ensure!(data.len() <= MAX_STATE, "Input exceeds limit");
        Ok(Self { data, at: 0 })
    }
    pub fn u32(&mut self) -> Result<u32> {
        ensure!(
            self.data.len().saturating_sub(self.at) >= 4,
            "Truncated integer"
        );
        let value = u32::from_be_bytes(self.data[self.at..self.at + 4].try_into()?);
        self.at += 4;
        Ok(value)
    }
    pub fn bytes(&mut self, cap: usize) -> Result<&'a [u8]> {
        let len = self.u32()? as usize;
        ensure!(
            len <= cap && len <= self.data.len().saturating_sub(self.at),
            "Invalid field length"
        );
        let value = &self.data[self.at..self.at + len];
        self.at += len;
        Ok(value)
    }
    pub fn list(&mut self, count_cap: usize, item_cap: usize) -> Result<Vec<Vec<u8>>> {
        let count = self.u32()? as usize;
        ensure!(count <= count_cap, "Too many entries");
        (0..count)
            .map(|_| self.bytes(item_cap).map(Vec::from))
            .collect()
    }
    pub fn finish(self) -> Result<()> {
        ensure!(self.at == self.data.len(), "Trailing bytes");
        Ok(())
    }
}
pub fn put_u32(out: &mut Vec<u8>, n: usize) {
    out.extend_from_slice(&(n as u32).to_be_bytes());
}
pub fn put_bytes(out: &mut Vec<u8>, value: &[u8]) {
    put_u32(out, value.len());
    out.extend_from_slice(value);
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn untrusted_lengths_do_not_allocate() {
        assert!(Reader::new(&[255; 4]).unwrap().bytes(32).is_err());
        assert!(Reader::new(&[0, 0, 0, 3, 1]).unwrap().bytes(32).is_err());
        assert!(Reader::new(&[0]).unwrap().finish().is_err());
    }
}
